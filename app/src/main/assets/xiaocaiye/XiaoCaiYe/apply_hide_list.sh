#!/system/bin/sh
# 小助手 · 应用隐藏黑白名单「一键配置」
#
# 输入（由 AppHideListScreen 生成并推送到模块目录）：
#   隐藏黑名单.txt  每行: 包名,UID     -> 需要隐藏的应用
#   隐藏白名单.txt  每行: 包名         -> 明确不隐藏的应用
#
# 产出：
#   1) /data/adb/tricky_store/target.txt        隐藏名单（Tricky Store 证书/隐藏目标）
#   2) /data/adb/ap/package_config              排除列表（exclude=1 对该应用隐藏 root）
#
# 未在本次名单中出现的应用，其既有的 package_config 配置原样保留，不会被重置。

MODULE_DIR="/data/adb/XiaoCaiYe"
BLACK="$MODULE_DIR/隐藏黑名单.txt"
WHITE="$MODULE_DIR/隐藏白名单.txt"
BLACK_PKG="$MODULE_DIR/.black_pkg.tmp"
TEMP_TARGET="$MODULE_DIR/.target.tmp"
FINAL_TARGET="/data/adb/tricky_store/target.txt"
PKG_CONF_DIR="/data/adb/ap"
PACKAGE_CONFIG="$PKG_CONF_DIR/package_config"
TEMP_CONFIG="$PKG_CONF_DIR/package_config.new"

mkdir -p "$MODULE_DIR" /data/adb/tricky_store "$PKG_CONF_DIR"
touch "$BLACK" "$WHITE"
rm -f "$BLACK_PKG" "$TEMP_TARGET" "$TEMP_TARGET.2" "$TEMP_CONFIG"

cut -d',' -f1 "$BLACK" | grep -vE '^[[:space:]]*$' | sort -u >"$BLACK_PKG"

# ---------- 1. Tricky Store 隐藏名单 ----------
{
	echo "android"
	echo "com.google.android.gms"
	echo "com.google.android.gsf"
	echo "com.android.vending"
	cat "$BLACK_PKG"
} | grep -vE '^[[:space:]]*$' | sort -u >"$TEMP_TARGET"

# 白名单优先级最高：即使命中规则也强制移出隐藏名单
if [ -s "$WHITE" ]; then
	if grep -Fvxf "$WHITE" "$TEMP_TARGET" >"$TEMP_TARGET.2" 2>/dev/null; then
		mv "$TEMP_TARGET.2" "$TEMP_TARGET"
	fi
fi

if [ -f "$FINAL_TARGET" ] && cmp -s "$TEMP_TARGET" "$FINAL_TARGET" 2>/dev/null; then
	echo "隐藏名单(target.txt) 已是最新"
else
	mv "$TEMP_TARGET" "$FINAL_TARGET"
	chmod 0644 "$FINAL_TARGET"
	echo "隐藏名单(target.txt) 已更新，共 $(wc -l <"$FINAL_TARGET" | tr -d ' ') 个应用"
fi

# ---------- 2. FolkPatch/APatch 排除列表 ----------
echo "pkg,exclude,allow,uid,to_uid,sctx" >"$TEMP_CONFIG"

if [ -f "$PACKAGE_CONFIG" ]; then
	while IFS= read -r line || [ -n "$line" ]; do
		[ -z "$line" ] && continue
		case "$line" in
		pkg,*) continue ;;
		esac
		p=$(echo "$line" | awk -F',' '{print $1}')
		[ -z "$p" ] && continue
		# 本次重新决策过的包跳过，稍后按新结果写入
		if grep -qxF "$p" "$BLACK_PKG" 2>/dev/null; then continue; fi
		if grep -qxF "$p" "$WHITE" 2>/dev/null; then continue; fi
		echo "$line" >>"$TEMP_CONFIG"
	done <"$PACKAGE_CONFIG"
fi

# 黑名单：对该应用隐藏 root
while IFS= read -r line || [ -n "$line" ]; do
	[ -z "$line" ] && continue
	p=$(echo "$line" | awk -F',' '{print $1}')
	u=$(echo "$line" | awk -F',' '{print $2}' | tr -d ' ')
	[ -z "$p" ] && continue
	echo "$u" | grep -qE '^[0-9]+$' ||
		u=$(pm list packages -U 2>/dev/null |
			sed -n "s/^package:${p} uid:\([0-9]\{1,\}\).*/\1/p" | head -1)
	echo "$u" | grep -qE '^[0-9]+$' || continue
	echo "$p,1,0,$u,0,u:r:untrusted_app:s0" >>"$TEMP_CONFIG"
done <"$BLACK"

if cmp -s "$PACKAGE_CONFIG" "$TEMP_CONFIG" 2>/dev/null; then
	echo "排除列表(package_config) 已是最新"
else
	mv "$TEMP_CONFIG" "$PACKAGE_CONFIG"
	chmod 0644 "$PACKAGE_CONFIG"
	echo "排除列表(package_config) 已更新"
fi

rm -f "$BLACK_PKG" "$TEMP_TARGET" "$TEMP_TARGET.2" "$TEMP_CONFIG"
echo "一键配置完成（重启应用或解锁后生效更彻底）"
