#!/system/bin/sh

MODULE_DIR="/data/adb/XiaoCaiYe"
TARGET_DIR="/data/adb/tricky_store"
USER_EXCLUSION="$MODULE_DIR/需要取消隐藏BL的用户包名.txt"
SYSTEM_ADDITION="$MODULE_DIR/需要添加的系统应用包名.txt"
TEMP_NEW="$MODULE_DIR/target.txt"
FINAL_TARGET="$TARGET_DIR/target.txt"
TEMP_COMPARE="$MODULE_DIR/target.compare"

# 创建必要的目录和文件
mkdir -p "$MODULE_DIR"
mkdir -p "$TARGET_DIR"
touch "$USER_EXCLUSION"
touch "$SYSTEM_ADDITION"
rm -f "$TEMP_COMPARE"

# 生成新的包名列表
{
	echo "android"
	echo "com.google.android.gms"
	echo "com.google.android.gsf"
	echo "com.android.vending"
} >"$TEMP_NEW"

# 添加所有第三方应用
pm list packages -3 | sed 's/package://g' >>"$TEMP_NEW"

# 排序和去重
sort -u "$TEMP_NEW" -o "$TEMP_NEW"

# 应用排除列表
if [ -s "$USER_EXCLUSION" ]; then
	grep -Fvxf "$USER_EXCLUSION" "$TEMP_NEW" >"$TEMP_COMPARE"
else
	cp "$TEMP_NEW" "$TEMP_COMPARE"
fi

# 添加系统应用
if [ -s "$SYSTEM_ADDITION" ]; then
	cat "$SYSTEM_ADDITION" >>"$TEMP_COMPARE"
	# 再次排序去重
	sort -u "$TEMP_COMPARE" -o "$TEMP_COMPARE"
fi

# 更新目标文件
if [ -f "$FINAL_TARGET" ]; then
	if cmp -s "$TEMP_COMPARE" "$FINAL_TARGET" 2>/dev/null; then
		echo -e "当前包名列表已是最新\n"
		rm -f "$TEMP_COMPARE"
	else
		mv "$TEMP_COMPARE" "$FINAL_TARGET"
		echo -e "包名列表已更新\n"
	fi
else
	mv "$TEMP_COMPARE" "$FINAL_TARGET"
	echo -e "配置包名列表完成\n"
fi