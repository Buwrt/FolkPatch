#!/system/bin/sh

MODDIR=${0%/*}
MODULE_ROOT=$(dirname "$MODDIR")

TARGET_DIR="/data/adb/tricky_store"
TARGET_FILE="$TARGET_DIR/keybox.xml"
TARGET_FILE_TMP="$TARGET_DIR/keybox.xml.tmp"
CONFIG_DIR="/data/adb/XiaoCaiYe"
LOG_DIR="/data/adb/XiaoCaiYe"
LOG_FILE="$LOG_DIR/keybox自动更新.log"

KEYBOX_URL="https://dcdn.xiaocaiye.cn/keybox/keybox.xml"
KEYBOX_MD5_URL="https://dcdn.xiaocaiye.cn/keybox/keybox_md5.txt"
mkdir -p "$CONFIG_DIR"
mkdir -p "$TARGET_DIR"

>"$LOG_FILE"

log() {
	local timestamp=$(date '+%Y-%m-%d %H:%M:%S')
	echo "$1"
	echo "[$timestamp] $1" >>"$LOG_FILE"
}

calculate_md5() {
	local file="$1"
	if [ -f "$file" ]; then
		md5sum "$file" | cut -d' ' -f1
	else
		echo ""
	fi
}

download_md5() {
	local temp_md5_file="/data/local/tmp/keybox_md5_$$.tmp"
	local remote_md5=""
	curl -s "$KEYBOX_MD5_URL" -o "$temp_md5_file" 2>/dev/null
	if [ $? -eq 0 ] && [ -s "$temp_md5_file" ]; then
		remote_md5=$(cat "$temp_md5_file" | tr -cd 'a-fA-F0-9')
		rm -f "$temp_md5_file"
		if [ ${#remote_md5} -eq 32 ]; then
			echo "$remote_md5"
			return 0
		fi
	fi
	rm -f "$temp_md5_file" 2>/dev/null
	echo ""
	return 1
}

log "开始检查 keybox 版本"

if ! ping -c 1 -W 2 dcdn.xiaocaiye.cn >/dev/null 2>&1; then
	log "连接服务器失败"
	exit 1
fi

remote_md5=$(download_md5)
if [ -z "$remote_md5" ]; then
	log "MD5校验文件获取失败"
	exit 1
fi

if [ -f "$TARGET_FILE" ]; then
	local_md5=$(calculate_md5 "$TARGET_FILE")
	if [ "$local_md5" = "$remote_md5" ]; then
		log "当前 keybox 已是最新版本"
		exit 0
	else
		log "开始更新 keybox"
	fi
else
	log "开始获取 keybox"
fi

curl -s "$KEYBOX_URL" -o "$TARGET_FILE_TMP"

if [ $? -ne 0 ]; then
	log "keybox.xml 文件获取失败"
	rm -f "$TARGET_FILE_TMP"
	exit 1
fi

local_md5=$(calculate_md5 "$TARGET_FILE_TMP")

if [ "$local_md5" = "$remote_md5" ]; then
	mv "$TARGET_FILE_TMP" "$TARGET_FILE"
	log "keybox 已更新至最新版本"
else
	log "keybox 更新失败"
	rm -f "$TARGET_FILE_TMP"
	exit 1
fi
