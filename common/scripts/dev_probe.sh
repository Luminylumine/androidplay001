#!/system/bin/sh
# 列出 /dev 和 /sys/class 中 shell (uid=2000) 实际可 ioctl/write/open 的特殊节点
# 不解析 dmesg/selinux audit，纯 syscall 试

TMP=/data/local/tmp/dev_writable.$$
rm -f $TMP

check_chr() {
    local d="$1"
    [ -e "$d" ] || return
    local perm=$(ls -ld "$d" 2>/dev/null)
    # test ioctl(0, FIONREAD, 0) ; open O_RDWR
    ( exec 9<> "$d" ) 2>/dev/null && echo "rw: $perm" >>$TMP && return
    ( exec 9< "$d" ) 2>/dev/null && echo "ro: $perm" >>$TMP
}

for c in /dev/binder /dev/hwbinder /dev/vndbinder \
  /dev/mali0 /dev/ion \
  /dev/fuse /dev/ashmem \
  /dev/dma_heap/system /dev/dma_heap/system_uncached /dev/dma_heap/linux,cma \
  /dev/uinput /dev/input/event0 /dev/input/event1 /dev/input/event2 /dev/input/event3 /dev/input/event4 \
  /dev/tty /dev/tty0 /dev/ptmx /dev/pts/ptmx \
  /dev/random /dev/urandom /dev/null /dev/zero /dev/full \
  /dev/snd/timer /dev/snd/pcmC0D0c /dev/snd/pcmC0D0p /dev/snd/controlC0 \
  /dev/v4l-subdev0 /dev/video0 /dev/video1 /dev/video10 /dev/video11 \
  /dev/block/mmcblk0 /dev/block/mmcblk0p1 /dev/block/mmcblk0p10 /dev/block/mmcblk0p20 \
  /dev/block/by-name/system /dev/block/by-name/vendor /dev/block/by-name/product \
  /dev/kmsg /dev/msg0 /dev/msg1 /dev/msg2 \
  /dev/rtc0 /dev/watchdog /dev/watchdog0 \
  /dev/fb0 /dev/graphics/fb0 /dev/ttyGS0 \
  ; do
  check_chr "$c"
done

echo "--- /dev 总览 (组非root)："
ls -la /dev 2>/dev/null | awk '$3!="root" || $4!="root" {print}'

echo "--- shell 可 ioctl/write/open 的节点："
sort -u $TMP 2>/dev/null
rm -f $TMP
