use anyhow::{anyhow, bail, Context, Result};
use async_compression::tokio::bufread::{BzDecoder, XzDecoder, ZstdDecoder};
use jni::objects::{JObject, JString};
use jni::sys::{jboolean, jint, jlong};
use jni::JNIEnv;
use payload_dumper::payload::payload_dumper::AsyncPayloadRead;
use payload_dumper::payload::payload_parser::{parse_local_payload, parse_local_zip_payload};
use payload_dumper::readers::local_reader::LocalAsyncPayloadReader;
use payload_dumper::readers::local_zip_reader::LocalAsyncZipPayloadReader;
use payload_dumper::structs::{install_operation, DeltaArchiveManifest, Extent, InstallOperation};
use sha2::{Digest, Sha256};
use std::collections::HashMap;
use std::fs::File as StdFile;
use std::io::Read as StdRead;
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::sync::{Arc, Mutex, OnceLock};
use tokio::fs::{self, File, OpenOptions};
use tokio::io::{AsyncRead, AsyncReadExt, AsyncSeekExt, AsyncWriteExt, BufReader};
use tokio::runtime::{Builder, Runtime};
use tokio::sync::Semaphore;
use tokio::task::JoinSet;

const IO_BUFFER_SIZE: usize = 512 * 1024;
const ZIP_BUFFER_SIZE: usize = 256 * 1024;
const MAX_WORKERS: usize = 8;

struct LoadedPayload {
    manifest: DeltaArchiveManifest,
    data_offset: u64,
    reader: Arc<dyn AsyncPayloadRead>,
}

struct ProgressState {
    completed: AtomicU64,
    total: AtomicU64,
    cancelled: AtomicBool,
}

static RUNTIME: OnceLock<Runtime> = OnceLock::new();
static PAYLOAD_CACHE: OnceLock<Mutex<Option<(String, Arc<LoadedPayload>)>>> = OnceLock::new();
static PROGRESS: OnceLock<Mutex<HashMap<i64, Arc<ProgressState>>>> = OnceLock::new();

fn runtime() -> Result<&'static Runtime> {
    if let Some(rt) = RUNTIME.get() {
        return Ok(rt);
    }
    let workers = std::thread::available_parallelism()
        .map(|n| n.get().min(MAX_WORKERS).max(2))
        .unwrap_or(4);
    let rt = Builder::new_multi_thread()
        .worker_threads(workers)
        .enable_all()
        .thread_name("payload-jni")
        .build()
        .context("create payload runtime")?;
    let _ = RUNTIME.set(rt);
    RUNTIME
        .get()
        .ok_or_else(|| anyhow!("payload runtime unavailable"))
}

async fn parse_input(path: &str) -> Result<Arc<LoadedPayload>> {
    let mut magic = [0u8; 4];
    StdFile::open(path)
        .with_context(|| format!("open input: {path}"))?
        .read_exact(&mut magic)
        .with_context(|| format!("read input header: {path}"))?;

    let path_buf = PathBuf::from(path);
    let (manifest, data_offset, reader): (_, _, Arc<dyn AsyncPayloadRead>) =
        if magic.starts_with(b"PK\x03\x04") || magic.starts_with(b"PK\x05\x06") {
            let (manifest, data_offset, _) = parse_local_zip_payload(path_buf.clone())
                .await
                .context("parse OTA ZIP payload")?;
            let reader = LocalAsyncZipPayloadReader::new(path_buf)
                .await
                .context("open OTA ZIP payload reader")?;
            (manifest, data_offset, Arc::new(reader))
        } else {
            let (manifest, data_offset) = parse_local_payload(Path::new(path))
                .await
                .context("parse payload.bin")?;
            let reader = LocalAsyncPayloadReader::new(path_buf)
                .await
                .context("open payload.bin reader")?;
            (manifest, data_offset, Arc::new(reader))
        };

    Ok(Arc::new(LoadedPayload {
        manifest,
        data_offset,
        reader,
    }))
}

async fn cached_payload(path: &str) -> Result<Arc<LoadedPayload>> {
    let cache = PAYLOAD_CACHE.get_or_init(|| Mutex::new(None));
    if let Some((cached_path, payload)) = cache
        .lock()
        .map_err(|_| anyhow!("payload cache poisoned"))?
        .as_ref()
    {
        if cached_path == path {
            return Ok(payload.clone());
        }
    }
    let parsed = parse_input(path).await?;
    let mut guard = cache
        .lock()
        .map_err(|_| anyhow!("payload cache poisoned"))?;
    *guard = Some((path.to_string(), parsed.clone()));
    Ok(parsed)
}

fn supported_type(op: &InstallOperation) -> bool {
    matches!(
        op.r#type(),
        install_operation::Type::Replace
            | install_operation::Type::ReplaceXz
            | install_operation::Type::ReplaceBz
            | install_operation::Type::Zstd
            | install_operation::Type::Zero
            | install_operation::Type::Discard
    )
}

fn extent_bytes(extent: &Extent, block_size: u64, image_size: u64) -> Result<(u64, u64, u64)> {
    let start = extent
        .start_block
        .ok_or_else(|| anyhow!("extent missing start_block"))?;
    let blocks = extent
        .num_blocks
        .ok_or_else(|| anyhow!("extent missing num_blocks"))?;
    if blocks == 0 {
        bail!("zero-length destination extent");
    }
    let offset = start
        .checked_mul(block_size)
        .ok_or_else(|| anyhow!("extent offset overflow"))?;
    let len = blocks
        .checked_mul(block_size)
        .ok_or_else(|| anyhow!("extent size overflow"))?;
    let end = offset
        .checked_add(len)
        .ok_or_else(|| anyhow!("extent end overflow"))?;
    if end > image_size {
        bail!("destination extent exceeds image size");
    }
    Ok((offset, len, end))
}

fn validate_partition(
    partition: &payload_dumper::structs::PartitionUpdate,
    block_size: u64,
    image_size: u64,
) -> Result<u64> {
    let mut ranges: Vec<(u64, u64)> = Vec::new();
    let mut work = 0u64;
    for (index, op) in partition.operations.iter().enumerate() {
        if !supported_type(op) {
            bail!(
                "operation {index} ({:?}) needs the CLI fallback",
                op.r#type()
            );
        }
        if op.dst_extents.is_empty() {
            bail!("operation {index} has no destination extents");
        }
        if !matches!(
            op.r#type(),
            install_operation::Type::Zero | install_operation::Type::Discard
        ) && (op.data_offset.is_none() || op.data_length.is_none())
        {
            bail!("operation {index} is missing payload data range");
        }
        for ext in &op.dst_extents {
            let (offset, len, end) = extent_bytes(ext, block_size, image_size)
                .with_context(|| format!("invalid destination range in operation {index}"))?;
            ranges.push((offset, end));
            work = work
                .checked_add(len)
                .ok_or_else(|| anyhow!("work size overflow"))?;
        }
    }
    ranges.sort_unstable_by_key(|r| r.0);
    for pair in ranges.windows(2) {
        if pair[0].1 > pair[1].0 {
            bail!("overlapping destination extents; use the sequential CLI fallback");
        }
    }
    if work == 0 {
        bail!("partition has no extractable operations");
    }
    Ok(work)
}

async fn write_stream_to_extents<R>(
    input: &mut R,
    output: &mut File,
    extents: &[Extent],
    block_size: u64,
    progress: &ProgressState,
) -> Result<u64>
where
    R: AsyncRead + Unpin,
{
    let mut buf = vec![0u8; IO_BUFFER_SIZE];
    let mut extent_index = 0usize;
    let mut extent_written = 0u64;
    let mut total_written = 0u64;
    loop {
        if progress.cancelled.load(Ordering::Relaxed) {
            bail!("cancelled");
        }
        let read = input.read(&mut buf).await?;
        if read == 0 {
            break;
        }
        let mut consumed = 0usize;
        while consumed < read {
            let extent = extents
                .get(extent_index)
                .ok_or_else(|| anyhow!("operation produced more data than destination extents"))?;
            let (extent_start, extent_len, _) = extent_bytes(extent, block_size, u64::MAX)?;
            let remaining = extent_len - extent_written;
            let part = remaining.min((read - consumed) as u64) as usize;
            output
                .seek(std::io::SeekFrom::Start(extent_start + extent_written))
                .await?;
            output.write_all(&buf[consumed..consumed + part]).await?;
            consumed += part;
            extent_written += part as u64;
            total_written += part as u64;
            progress.completed.fetch_add(part as u64, Ordering::Relaxed);
            if extent_written == extent_len {
                extent_index += 1;
                extent_written = 0;
            }
        }
    }
    if extent_index != extents.len() || extent_written != 0 {
        bail!("operation output length does not match destination extents");
    }
    Ok(total_written)
}

async fn extract_data_operation(
    op: &InstallOperation,
    reader_source: &dyn AsyncPayloadRead,
    data_offset: u64,
    block_size: u64,
    output_path: &Path,
    progress: &ProgressState,
) -> Result<()> {
    let op_offset = op
        .data_offset
        .ok_or_else(|| anyhow!("missing operation data offset"))?;
    let op_len = op
        .data_length
        .ok_or_else(|| anyhow!("missing operation data length"))?;
    let absolute_offset = data_offset
        .checked_add(op_offset)
        .ok_or_else(|| anyhow!("payload offset overflow"))?;
    let mut reader = reader_source.open_reader().await?;
    let stream = reader.read_range(absolute_offset, op_len).await?;
    let mut output = OpenOptions::new().write(true).open(output_path).await?;
    let expected: u64 = op
        .dst_extents
        .iter()
        .map(|e| extent_bytes(e, block_size, u64::MAX).map(|x| x.1))
        .collect::<Result<Vec<_>>>()?
        .into_iter()
        .sum();

    let written = match op.r#type() {
        install_operation::Type::Replace => {
            let mut stream = stream;
            write_stream_to_extents(
                &mut stream,
                &mut output,
                &op.dst_extents,
                block_size,
                progress,
            )
            .await?
        }
        install_operation::Type::ReplaceXz => {
            let mut decoder = XzDecoder::new(BufReader::with_capacity(ZIP_BUFFER_SIZE, stream));
            write_stream_to_extents(
                &mut decoder,
                &mut output,
                &op.dst_extents,
                block_size,
                progress,
            )
            .await?
        }
        install_operation::Type::ReplaceBz => {
            let mut decoder = BzDecoder::new(BufReader::with_capacity(ZIP_BUFFER_SIZE, stream));
            write_stream_to_extents(
                &mut decoder,
                &mut output,
                &op.dst_extents,
                block_size,
                progress,
            )
            .await?
        }
        install_operation::Type::Zstd => {
            let mut decoder = ZstdDecoder::new(BufReader::with_capacity(ZIP_BUFFER_SIZE, stream));
            write_stream_to_extents(
                &mut decoder,
                &mut output,
                &op.dst_extents,
                block_size,
                progress,
            )
            .await?
        }
        _ => bail!("unsupported data operation"),
    };
    output.flush().await?;
    if written != expected {
        bail!("operation wrote {written} bytes, expected {expected}");
    }
    Ok(())
}

async fn verify_image(path: &Path, expected_hash: &[u8]) -> Result<()> {
    if expected_hash.is_empty() {
        return Ok(());
    }
    let mut file = File::open(path).await?;
    let mut digest = Sha256::new();
    let mut buf = vec![0u8; 1024 * 1024];
    loop {
        let n = file.read(&mut buf).await?;
        if n == 0 {
            break;
        }
        digest.update(&buf[..n]);
    }
    let got = digest.finalize();
    if got.as_slice() != expected_hash {
        bail!("partition SHA-256 mismatch");
    }
    Ok(())
}

async fn extract_partition(
    input: &str,
    output_dir: &str,
    partition_name: &str,
    threads: usize,
    verify: bool,
    state: Arc<ProgressState>,
) -> Result<()> {
    if partition_name.is_empty()
        || partition_name.contains('/')
        || partition_name.contains('\\')
        || partition_name == "."
        || partition_name == ".."
    {
        bail!("invalid partition name");
    }
    let payload = cached_payload(input).await?;
    if state.cancelled.load(Ordering::Relaxed) {
        bail!("cancelled");
    }
    let partition = payload
        .manifest
        .partitions
        .iter()
        .find(|p| p.partition_name == partition_name)
        .cloned()
        .ok_or_else(|| anyhow!("partition not found: {partition_name}"))?;
    let block_size = u64::from(payload.manifest.block_size.unwrap_or(4096));
    let image_size = partition
        .new_partition_info
        .as_ref()
        .and_then(|i| i.size)
        .ok_or_else(|| anyhow!("partition image size missing"))?;
    let work_total = validate_partition(&partition, block_size, image_size)?;
    if image_size == 0 {
        bail!("partition image is empty");
    }
    // Use logical bytes covered by operations for a stable progress denominator; sparse gaps
    // are already zero-filled by set_len and do not require I/O.
    state.total.store(work_total, Ordering::Relaxed);
    if state.cancelled.load(Ordering::Relaxed) {
        bail!("cancelled");
    }
    let out_path = PathBuf::from(output_dir).join(format!("{partition_name}.img"));
    if let Some(parent) = out_path.parent() {
        fs::create_dir_all(parent).await?;
    }
    let output = File::create(&out_path).await?;
    output.set_len(image_size).await?;
    drop(output);

    let workers = threads.max(1).min(MAX_WORKERS);
    let semaphore = Arc::new(Semaphore::new(workers));
    let mut tasks = JoinSet::new();
    for op in partition.operations.iter().cloned() {
        let semaphore = semaphore.clone();
        let reader = payload.reader.clone();
        let output_path = out_path.clone();
        let progress = state.clone();
        let data_offset = payload.data_offset;
        tasks.spawn(async move {
            let _permit = semaphore
                .acquire_owned()
                .await
                .map_err(|e| anyhow!(e.to_string()))?;
            if progress.cancelled.load(Ordering::Relaxed) {
                bail!("cancelled");
            }
            match op.r#type() {
                install_operation::Type::Zero | install_operation::Type::Discard => {
                    let logical_bytes = op
                        .dst_extents
                        .iter()
                        .map(|e| extent_bytes(e, block_size, image_size).map(|x| x.1))
                        .collect::<Result<Vec<_>>>()?
                        .into_iter()
                        .sum::<u64>();
                    progress
                        .completed
                        .fetch_add(logical_bytes, Ordering::Relaxed);
                    Ok(())
                }
                _ => {
                    extract_data_operation(
                        &op,
                        reader.as_ref(),
                        data_offset,
                        block_size,
                        &output_path,
                        progress.as_ref(),
                    )
                    .await
                }
            }
        });
    }
    while let Some(joined) = tasks.join_next().await {
        joined.context("join extraction worker")??;
    }

    if state.cancelled.load(Ordering::Relaxed) {
        bail!("cancelled");
    }
    if verify {
        if let Some(hash) = partition
            .new_partition_info
            .as_ref()
            .and_then(|i| i.hash.as_deref())
        {
            verify_image(&out_path, hash).await?;
        }
    }
    state.completed.store(work_total, Ordering::Relaxed);
    let actual_size = fs::metadata(&out_path).await?.len();
    if actual_size != image_size {
        bail!("image length mismatch: {actual_size} != {image_size}");
    }
    Ok(())
}

fn to_rust_string(env: &mut JNIEnv<'_>, value: JString<'_>) -> Result<String> {
    Ok(env.get_string(&value)?.into())
}

fn throw_runtime(env: &mut JNIEnv<'_>, message: &str) {
    let _ = env.throw_new("java/lang/RuntimeException", message);
}

#[no_mangle]
pub extern "system" fn Java_com_mcai_ubuntudsu_core_dna_PayloadExtractNative_extractPartition(
    mut env: JNIEnv<'_>,
    _this: JObject<'_>,
    input: JString<'_>,
    output_dir: JString<'_>,
    partition_name: JString<'_>,
    threads: jint,
    verify: jboolean,
    token: jlong,
) {
    let result = (|| -> Result<()> {
        let input = to_rust_string(&mut env, input)?;
        let output_dir = to_rust_string(&mut env, output_dir)?;
        let partition_name = to_rust_string(&mut env, partition_name)?;
        let progress = Arc::new(ProgressState {
            completed: AtomicU64::new(0),
            total: AtomicU64::new(1),
            cancelled: AtomicBool::new(false),
        });
        PROGRESS
            .get_or_init(|| Mutex::new(HashMap::new()))
            .lock()
            .map_err(|_| anyhow!("progress map poisoned"))?
            .insert(token as i64, progress.clone());
        runtime()?.block_on(extract_partition(
            &input,
            &output_dir,
            &partition_name,
            threads.max(1) as usize,
            verify != 0,
            progress,
        ))
    })();
    if let Some(map) = PROGRESS.get() {
        if let Ok(mut map) = map.lock() {
            map.remove(&(token as i64));
        }
    }
    if let Err(e) = result {
        throw_runtime(&mut env, &format!("{e:#}"));
    }
}

#[no_mangle]
pub extern "system" fn Java_com_mcai_ubuntudsu_core_dna_PayloadExtractNative_getExtractProgress(
    _env: JNIEnv<'_>,
    _this: JObject<'_>,
    token: jlong,
) -> jlong {
    let Some(map) = PROGRESS.get() else {
        return -1;
    };
    let Ok(map) = map.lock() else {
        return -1;
    };
    let Some(progress) = map.get(&(token as i64)) else {
        return -1;
    };
    let total = progress.total.load(Ordering::Relaxed).max(1);
    let permille = ((progress.completed.load(Ordering::Relaxed).min(total) * 1000) / total) as i64;
    (1_i64 << 16) | permille
}

#[no_mangle]
pub extern "system" fn Java_com_mcai_ubuntudsu_core_dna_PayloadExtractNative_cancelExtract(
    _env: JNIEnv<'_>,
    _this: JObject<'_>,
    token: jlong,
) {
    if let Some(map) = PROGRESS.get() {
        if let Ok(map) = map.lock() {
            if let Some(progress) = map.get(&(token as i64)) {
                progress.cancelled.store(true, Ordering::Relaxed);
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn extent_offsets_are_checked_against_image_bounds() {
        let extent = Extent {
            start_block: Some(2),
            num_blocks: Some(3),
        };
        assert_eq!(
            extent_bytes(&extent, 4096, 32768).unwrap(),
            (8192, 12288, 20480)
        );
        assert!(extent_bytes(&extent, 4096, 20000).is_err());
    }

    #[tokio::test]
    async fn stream_writer_splits_data_across_target_extents() {
        let unique = std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .unwrap()
            .as_nanos();
        let path = std::env::temp_dir().join(format!(
            "payload-extents-{}-{unique}.img",
            std::process::id()
        ));
        let initial = File::create(&path).await.unwrap();
        initial.set_len(12).await.unwrap();
        drop(initial);

        let mut output = OpenOptions::new().write(true).open(&path).await.unwrap();
        let mut input = std::io::Cursor::new(b"abcdefgh".to_vec());
        let extents = [
            Extent {
                start_block: Some(1),
                num_blocks: Some(1),
            },
            Extent {
                start_block: Some(2),
                num_blocks: Some(1),
            },
        ];
        let progress = ProgressState {
            completed: AtomicU64::new(0),
            total: AtomicU64::new(8),
            cancelled: AtomicBool::new(false),
        };
        let count = write_stream_to_extents(&mut input, &mut output, &extents, 4, &progress)
            .await
            .unwrap();
        output.flush().await.unwrap();
        drop(output);

        let image = std::fs::read(&path).unwrap();
        assert_eq!(count, 8);
        assert_eq!(&image[..4], &[0, 0, 0, 0]);
        assert_eq!(&image[4..], b"abcdefgh");
        assert_eq!(progress.completed.load(Ordering::Relaxed), 8);
        std::fs::remove_file(path).unwrap();
    }
}
