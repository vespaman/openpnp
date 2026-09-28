"""Extract the pipeline a part or package really uses and make it runnable against a captured frame:
the capture stage becomes an image read. The result is what Benchmark.java times.

    make_pipeline.py <part-or-package-id> <captured-frame.png> <out.xml> <config.xml>...

where the config files are the ones on the machine: parts.xml, packages.xml and vision-settings.xml.
"""
import re
import sys

partId, imagePath, out = sys.argv[1:4]
configs = sys.argv[4:]

settingsId = None
for name in configs:
    m = re.search(r'<(part|package)\b[^>]*\bid="%s"[^>]*' % re.escape(partId), open(name).read())
    if m:
        settingsId = re.search(r'bottom-vision-id="([^"]+)"', m.group(0))
        if settingsId:
            settingsId = settingsId.group(1)
            break
if settingsId is None:
    sys.exit("no part or package with id %s naming a bottom vision settings, in %s"
            % (partId, ", ".join(configs)))

pipeline = None
for name in configs:
    m = re.search(r'<vision-settings [^>]*id="%s"(.*?)</vision-settings>' % re.escape(settingsId),
            open(name).read(), re.S)
    if m:
        pipeline = re.search(r'<cv-pipeline>.*?</cv-pipeline>', m.group(1), re.S)
        break
if pipeline is None:
    sys.exit("bottom vision settings %s not found" % settingsId)

xml = re.sub(r'<cv-stage class="org\.openpnp\.vision\.pipeline\.stages\.ImageCapture"[^>]*/>',
        '<cv-stage class="org.openpnp.vision.pipeline.stages.ImageRead" name="0" enabled="true" '
        'file="%s" color-space="Bgr" handle-as-captured="false"/>' % imagePath,
        pipeline.group(0), count=1)
if "ImageRead" not in xml:
    sys.exit("no ImageCapture stage - is this really a bottom vision pipeline?")
open(out, "w").write(xml)
print("%s: %d stages, settings %s, capture replaced by %s"
        % (out, xml.count("<cv-stage"), settingsId, imagePath))
