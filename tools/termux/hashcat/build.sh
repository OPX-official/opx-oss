TERMUX_PKG_HOMEPAGE=https://hashcat.net/hashcat/
TERMUX_PKG_DESCRIPTION="World's fastest and most advanced password recovery utility"
TERMUX_PKG_LICENSE="MIT"
# hashcat keeps its MIT text at docs/license.txt, not LICENSE in the source root. Without this
# the pinned tree's license step aborts after make install and no deb is produced at all, so
# the runtime silently shipped without the GPU cracker.
TERMUX_PKG_LICENSE_FILE="docs/license.txt"
TERMUX_PKG_MAINTAINER="OPX OSS <opx@opx.dev>"
TERMUX_PKG_VERSION=7.1.2
TERMUX_PKG_SRCURL=https://github.com/hashcat/hashcat/archive/refs/tags/v${TERMUX_PKG_VERSION}.tar.gz
TERMUX_PKG_SHA256=9546a6326d747530b44fcc079babad40304a87f32d3c9080016d58b39cfc8b96
# libOpenCL comes from the ICD loader; the vendor driver package supplies the device.
TERMUX_PKG_DEPENDS="libc++, libiconv, ocl-icd, opencl-vendor-driver"
TERMUX_PKG_BUILD_DEPENDS="ocl-icd"
TERMUX_PKG_BUILD_IN_SRC=true
# hashcat ships its own OpenCL headers and sse2neon in deps/, so only the loader is system.
# SHARED=1 installs the OpenCL kernels under share/hashcat; without them hashcat cannot
# start a single backend, even with a working GPU.
TERMUX_PKG_EXTRA_MAKE_ARGS="USE_SYSTEM_OPENCL=0 SHARED=1"

# The pinned termux tree names its job count TERMUX_PKG_MAKE_PROCESSES (setup_variables),
# not the older TERMUX_MAKE_PROCESSES: using the old name aborted the build with "unbound
# variable" and left the shipped runtime without a hashcat binary at all.
termux_step_make() {
	make -j"${TERMUX_PKG_MAKE_PROCESSES}" \
		PREFIX="${TERMUX_PREFIX}" \
		USE_SYSTEM_OPENCL=0 \
		SHARED=1
}

termux_step_make_install() {
	make install \
		PREFIX="${TERMUX_PREFIX}" \
		DESTDIR="${TERMUX_PKG_MASSAGEDIR}" \
		USE_SYSTEM_OPENCL=0 \
		SHARED=1
}
