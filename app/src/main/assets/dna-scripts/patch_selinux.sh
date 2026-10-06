if [ ! -d "$DNA_DIR/out/permissive" ];then
    mkdir -p $DNA_DIR/out/permissive
fi
cd $DNA_DIR/out/permissive
for i in $IMG ;do
    line=$(echo "$i" | cut -d"." -f1)
    echo   "> 开始分解 ${i} 分区"
    magiskboot unpack -h $DNA_DIR/$i &>/dev/null
    echo   "> 开始插入宽容代码"
    header="$DNA_DIR/out/permissive/header"
    search_cmdline_index=$(grep -n "^cmdline=" $header | cut -d ":" -f 1)
    sed -i "s/androidboot.selinux=enforcing/androidboot.selinux=permissive/" $header
    sed -i "s/androidboot.selinux=permissive//g" $header
    sed -i "/^cmdline=/{s/$/& androidboot.selinux=permissive/}" $header
    sed -i -e 's;  *; ;g' -e 's;[ \t]*$;;' $header
    magiskboot repack $DNA_DIR/$i &>/dev/null
    echo   "> 开始合并 ${i} 分区"
    mv $DNA_DIR/out/permissive/new-boot.img $DNA_DIR/out/vendor_boot.img
    echo   "> 宽容完成，文件位于$DNA_DIR/out/vendor_boot.img"
    rm -rf $DNA_DIR/out/permissive
done

