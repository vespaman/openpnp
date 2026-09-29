#!/bin/bash
(sleep 5 && v4l2-ctl -d /dev/video2 -c exposure_time_absolute=50)&

platform='unknown'
unamestr=`uname`
case "$unamestr" in
	Linux)
		platform='linux'
		rootdir="$(dirname $(readlink -f $0))"
	;;
	Darwin)
		platform='mac'
		rootdir="$(cd $(dirname $0); pwd -P)"
	;;
esac

# nu.pattern.OpenCV loads libopencv_java from an unnamed module, which Java 25 warns about and a later
# release will refuse. Older JVMs do not know the option, so only pass it when the JVM accepts it.
native_access=""
if java --enable-native-access=ALL-UNNAMED -version >/dev/null 2>&1; then
	native_access="--enable-native-access=ALL-UNNAMED"
fi

case "$platform" in
	mac)
		java -Xdock:name=OpenPnP --add-opens=java.base/java.lang=ALL-UNNAMED --add-opens=java.desktop/java.awt=ALL-UNNAMED --add-opens=java.desktop/java.awt.color=ALL-UNNAMED $native_access -jar $rootdir/target/openpnp-gui-0.0.1-alpha-SNAPSHOT.jar
	;;
	linux)
		java $1 --add-opens=java.base/java.lang=ALL-UNNAMED --add-opens=java.desktop/java.awt=ALL-UNNAMED --add-opens=java.desktop/java.awt.color=ALL-UNNAMED $native_access -jar $rootdir/target/openpnp-gui-0.0.1-alpha-SNAPSHOT.jar
	;;
esac
