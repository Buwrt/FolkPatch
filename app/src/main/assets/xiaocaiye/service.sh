#!/system/bin/sh

# 获取当前模块目录路径
MODDIR=${0%/*}

# 配置
MARK_FILE="$MODDIR/已完成首次配置"
ZygiskD="/data/adb/modules/zygisksu/bin/zygiskd"

# ---------- 增强隐藏环境 ----------
check_reset_prop() {
	local NAME=$1
	local EXPECTED=$2
	local VALUE=$(resetprop $NAME)
	[ -z $VALUE ] || [ $VALUE = $EXPECTED ] || resetprop -n $NAME $EXPECTED
}
contains_reset_prop() {
	local NAME=$1
	local CONTAINS=$2
	local NEWVAL=$3
	[[ "$(resetprop $NAME)" = *"$CONTAINS"* ]] && resetprop -n $NAME $NEWVAL
}

resetprop -w sys.boot_completed 0
check_reset_prop "ro.boot.vbmeta.device_state" "locked"
check_reset_prop "ro.boot.verifiedbootstate" "green"
check_reset_prop "ro.boot.flash.locked" "1"
check_reset_prop "ro.boot.veritymode" "enforcing"
check_reset_prop "ro.boot.warranty_bit" "0"
check_reset_prop "ro.warranty_bit" "0"
check_reset_prop "ro.debuggable" "0"
check_reset_prop "ro.force.debuggable" "0"
check_reset_prop "ro.secure" "1"
check_reset_prop "ro.adb.secure" "1"
check_reset_prop "ro.build.type" "user"
check_reset_prop "ro.build.tags" "release-keys"
check_reset_prop "ro.crypto.state" "encrypted"
check_reset_prop "ro.vendor.boot.warranty_bit" "0"
check_reset_prop "ro.vendor.warranty_bit" "0"
check_reset_prop "vendor.boot.vbmeta.device_state" "locked"
check_reset_prop "vendor.boot.verifiedbootstate" "green"
check_reset_prop "sys.oem_unlock_allowed" "0"
check_reset_prop "ro.secureboot.lockstate" "locked"
check_reset_prop "ro.boot.realmebootstate" "green"
check_reset_prop "ro.boot.realme.lockstate" "1"
contains_reset_prop "ro.bootmode" "recovery" "unknown"
contains_reset_prop "ro.boot.bootmode" "recovery" "unknown"
contains_reset_prop "vendor.boot.bootmode" "recovery" "unknown"
# ----------------------------------------

# 等待系统启动完成
until [ $(getprop sys.boot_completed) -eq 1 ]; do
	sleep 5
done

# 配置环境变量
export PREFIX="${MODDIR}/arm64-v8a"
export LD_LIBRARY_PATH="${PREFIX}/lib"
export PATH="$(magisk --path)/.magisk/busybox:/system/bin:/system/xbin:/vendor/bin:${PREFIX}/bin:/product/bin:/apex/com.android.runtime/bin:/apex/com.android.art/bin:/system_ext/bin:/odm/bin:/vendor/xbin"

# 启动定时任务
crond -c $MODDIR/cron

# 仅自动配置一次
if [ ! -f "$MARK_FILE" ]; then
	"$ZygiskD" memory-type anonymous
	"$ZygiskD" denylist-policy whitelist
	"$ZygiskD" enforce-denylist just_umount
	"$MODDIR/unlock_run.sh" > "$MODDIR/unlock_run.log" 2>&1 &
	touch "$MARK_FILE"
fi

# 实时更新包名列表
inotifywait -m -e close_write,moved_to \
	--format '%T %e %f' \
	--timefmt '%FT%T' \
	/data/system | while read time event file; do

	case "$file" in
	packages.xml)
		[ "$APATCH" = "true" ] && sh $MODDIR/XiaoCaiYe/APatch.sh
		sh $MODDIR/XiaoCaiYe/update_packages.sh
		;;
	esac

done
