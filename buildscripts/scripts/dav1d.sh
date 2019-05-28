#!/bin/bash -e

. ../../include/path.sh

if [ "$1" == "build" ]; then
	true
elif [ "$1" == "clean" ]; then
	rm -rf _build$ndk_suffix
	exit 0
else
	exit 255
fi

# meson gets confused if this file does not permanently exist at the same location
mkdir -p _build$ndk_suffix
crossfile=_build$ndk_suffix/crossfile.txt
# also why can't it autodetect most of this?
cat >$crossfile <<AAA
[binaries]
c = '$CC'
cpp = '$CC -E'
ar = '$ndk_triple-ar'
strip = '$ndk_triple-strip'
[host_machine]
system = 'linux'
cpu_family = '${ndk_triple%%-*}'
cpu = '${CC%%-*}'
endian = 'little'
[paths]
prefix = '$prefix_dir'
AAA

# meson wants $CC to be the host's compiler
unset CC

meson _build$ndk_suffix \
	--buildtype release --cross-file $crossfile \
	--default-library static -Dbuild_tests=false

ninja -C _build$ndk_suffix -j$cores
ninja -C _build$ndk_suffix install
