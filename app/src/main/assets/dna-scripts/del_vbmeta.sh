if [ ! -d $DNA_DIR/out ];then
  mkdir -p $DNA_DIR/out
fi
for i in $IMG ;do
 info=$(dna gettype $DNA_DIR/$i)
 if [ "$info" = "vbmeta" ];then
   echo "> 正在去除$i验证"
   cp -rf $DNA_DIR/$i $DNA_DIR/out/$i
   magiskboot hexpatch $DNA_DIR/out/$i 0000000000000000617662746F6F6C20 0000000200000000617662746F6F6C20
   echo "> 完成，文件位于$DNA_DIR/out"
 else
   echo "> $i不支持去vbmeta验证！"
   continue
 fi
done