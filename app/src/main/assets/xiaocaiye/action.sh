#!/system/bin/sh

MODDIR=${0%/*}

# 配置环境变量
export PREFIX="${MODDIR}/arm64-v8a"
export LD_LIBRARY_PATH="${PREFIX}/lib"
export PATH="$(magisk --path)/.magisk/busybox:/system/bin:/system/xbin:/vendor/bin:${PREFIX}/bin:/product/bin:/apex/com.android.runtime/bin:/apex/com.android.art/bin:/system_ext/bin:/odm/bin:/vendor/xbin"

# 基本信息输出
echo -e "小助手面板\nby 传说中的小菜叶\n\n一键隐藏环境官网:\nhttps://xiaocaiye.cn/\n"
current_time=$(date "+%Y年-%m月-%d日 %H时:%M分:%S秒")
echo "当前时间: $current_time"

# ZygiskNext 相关路径
ZygiskD="/data/adb/modules/zygisksu/bin/zygiskd"
Root="/data/adb/zygisksu/denylist_policy"

# 模块数据目录
MR="/data/adb/XiaoCaiYe/"

# 音量键信号值
VOL_DOWN_SIGNAL=41
VOL_UP_SIGNAL=42

# 包名配置文件路径
YH="$MR需要取消隐藏BL的用户包名.txt"
XT="$MR需要添加的系统应用包名.txt"

# 未配置时的提示信息
AB="当前还没有设置哦，如需设置请前往: $MR\n\n说明:\n默认对全部用户安装的应用隐藏BL\n如果你需要对某个系统应用隐藏BL\n或者取消某个用户安装的应用隐藏BL\n请按照上方路径添加包名(每行一个)"

# 输出当前 Cron 表达式
echo -e "当前 Cron 表达式: $(sed -n '1s/sh.*//p' $MODDIR/cron/root)\n"

# 更新 TS 包名列表并显示当前配置
update() {
	echo
	sh $MODDIR/XiaoCaiYe/update_packages.sh
	head -n 10 /data/adb/tricky_store/target.txt
	echo -e "\n上面显示的有包名就代表更新完成啦！"

	echo -e "\n—————————— 当前取消隐藏BL的用户应用包名 ———————————\n"
	[ -s "$YH" ] && {
		cat "$YH"
		echo
	} || echo -e "$AB"

	echo -e "\n—————————— 当前需要隐藏BL的系统应用包名 ———————————\n"
	[ -s "$XT" ] && {
		cat "$XT"
		echo
	} || echo -e "$AB"

	echo -e "\n——————————————————————————————————————————"
}

# 监听音量键输入
Key_monitoring() {
    local key=$(keycheck ; echo $?)
    case $key in
        $VOL_UP_SIGNAL)   echo "0" ;;
        $VOL_DOWN_SIGNAL) echo "1" ;;
        *) return 0 ;;
    esac
}

show_root_option=true
if [ "$KSU" != "true" -a "$APATCH" != "true" ]; then
	if [ -f "$Root" ]; then
		policy_content=$(cat "$Root")
		if [ "$policy_content" = "1" ]; then
			echo "全局隐藏Root已开启"
		else
			echo "全局隐藏Root已关闭"
		fi
	else
		echo "全局隐藏Root状态未知！"
		show_root_option=false
	fi
fi

echo -e "—————————————————————————————————————"
if [ "$KSU" = "true" -o "$APATCH" = "true" ]; then
	if [ "$APATCH" = "true" ]; then
		# APatch 环境
		echo -e "按音量键 ＋:  更新keybox"
		echo -e "按音量键 －:  更新 TS 包名和 AP 排除列表"
	else
		# KernelSU 环境
		echo -e "按音量键 ＋:  更新keybox"
		echo -e "按音量键 －:  更新 Tricky_store 包名列表"
	fi
elif [ "$show_root_option" = true ]; then
	# Magisk/ZygiskNext 环境，Root 状态正常
	echo -e "按音量键 ＋:  开启/关闭 全局隐藏Root"
	echo -e "按音量键 －:  更新 keybox 和 TS 包名列表"
else
	# Root 状态未知
	echo -e "按音量键 －:  更新 keybox 和 TS 包名列表"
fi
echo -e "—————————————————————————————————————\n"

user_choice=$(Key_monitoring)

if [ "$KSU" = "true" -o "$APATCH" = "true" ]; then
	if [ "$APATCH" = "true" ]; then
		# APatch 环境操作
		if [[ "$user_choice" == "0" ]]; then
			sh $MODDIR/XiaoCaiYe/update_keybox.sh
		elif [[ "$user_choice" == "1" ]]; then
			echo "开始更新排除列表"
			sh $MODDIR/XiaoCaiYe/APatch.sh
			update
		fi
	else
		# KernelSU 环境操作
		if [[ "$user_choice" == "0" ]]; then
			sh $MODDIR/XiaoCaiYe/update_keybox.sh
		elif [[ "$user_choice" == "1" ]]; then
			update
		fi
	fi
else
	# Magisk/ZygiskNext 环境操作
	if [[ "$user_choice" == "0" ]] && [ "$show_root_option" = true ]; then
		# 切换全局隐藏Root状态
		if [ -f "$Root" ] && [ "$(cat "$Root")" = "1" ]; then
			$ZygiskD denylist-policy default
			echo "已关闭全局隐藏Root"
		else
			$ZygiskD denylist-policy whitelist
			echo "已开启全局隐藏Root"
		fi
	elif [[ "$user_choice" == "1" ]]; then
		sh $MODDIR/XiaoCaiYe/update_keybox.sh
		update
	fi
fi

if [ "$KSU" = "true" -o "$APATCH" = "true" ] && [ "$KSU_NEXT" != "true" ] && [ "$WKSU" != "true" ] && [ "$MMRL" != "true" ]; then
	echo -e "\n脚本执行完成，当前窗口将在 20 秒后自动关闭"
	sleep 20
fi
