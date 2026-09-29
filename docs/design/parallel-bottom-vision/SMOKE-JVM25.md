# Running OpenPnP on Temurin 25: what changed on the machine, and what to watch

Purpose: move the machine off a JVM that aborts (see `PRE-CHECK.md` §7) and prove it on the machine before any
of the pre-check work is built on top of it. Everything below is the launcher's own JVM selection, the
`--enable-native-access` warning, and a backup. **Nothing was changed inside the jar** - the machine still starts
the 2025-03-07 `openpnp-gui-0.0.1-alpha-SNAPSHOT.jar`, so this test moves one variable: the JVM.

## Already done on the OpenPnP PC

| File | Change | Backup |
|---|---|---|
| `~/openpnp/OpenPnP` | line 4: `INSTALL4J_JAVA_HOME_OVERRIDE=/opt/openjdk-bin-25` (was a commented placeholder) | `OpenPnP.bak-2026-09-29` |
| `~/openpnp/OpenPnP` | version ceiling `ver_major -gt 23` → `-gt 26` | same |
| `~/openpnp/OpenPnP.vmoptions` | added `--enable-native-access=ALL-UNNAMED` | `OpenPnP.vmoptions.bak-2026-09-29` |
| `/home/micke/openpnp_conf_backup_jvm25.tar.gz` | 28 MB, 356 entries: all config, jobs and boards | new |

Two things worth knowing, because both bit during setup:

- The ceiling is not cosmetic. Install4j walks `/usr/lib/jvm/*` and takes the first JVM inside its window, so
  before the ceiling moved, `sh -x ./OpenPnP` ended in `exec /usr/lib/jvm/openjdk-bin-11/bin/java` and ignored
  the override completely. If a step below shows `openjdk-bin-11`, this is the edit that did not take.
- `--enable-native-access` is unknown to Java 11 and the JVM refuses to start:
  `Unrecognized option: --enable-native-access=ALL-UNNAMED`. It is safe only because 25 is now pinned. **Delete
  that line before going back to 11**, or the machine will not start OpenPnP at all.

`~/.openpnp2` is 15 GB, of which `org.openpnp.vision.pipeline.stages.ImageWriteDebug` alone is 7.4 GB, so the
backup excludes the debug image directories (`org.openpnp*`, `backups`). Those are regenerable output, not
configuration; the 356 files that are configuration are all inside the archive.

## Run it

1. Only one instance: `pgrep -f install4j.org.openpnp.Main` must print nothing before starting.
2. Start OpenPnP exactly as usual (`~/openpnp/OpenPnP`), from a terminal if convenient - half of what is worth
   seeing goes to stdout/stderr only.
3. **Prove which JVM it is on**, within a few seconds of starting:

   ```
   ps -o args= -C java | head -1
   ```

   Must begin `/opt/openjdk-bin-25/bin/java --add-opens=java.base/java.lang=ALL-UNNAMED ...
   --enable-native-access=ALL-UNNAMED`. `openjdk-bin-11` means step "already done" did not apply.
4. Read the console while the configuration loads. Anything of this shape is a finding, and the line matters
   more than the stack:
   - `Unrecognized option` / `Error: Could not create the Java Virtual Machine` → JVM options.
   - `InaccessibleObjectException`, `IllegalAccessError` → a `--add-opens` that 25 needs and does not have.
   - `UnsatisfiedLinkError`, `NoSuchMethodError` in `nu.pattern.OpenCV`, `jSerialComm`, `bridj`, `jython`,
     `nashorn` or `batik` → the parts of the stack that nobody has validated on 25. This is the expected place
     for trouble, and it is the reason for this test.
   - `Restricted methods ... will be blocked in a future release` should now be **gone**; if it is still there,
     the vmoptions file was not picked up.
5. Configuration loads: machine tree present, `R0402` still on its 27 parts, both up cameras show a live image
   (the exposure poke `v4l2-ctl -d /dev/video2 -c exposure_time_absolute=50` is the last line of the launcher
   and runs as before).
6. Enable the machine, jog a few mm, home if that is normal for you. Then the job:
   `voltum_jobs/testjob_8-placements_rm10_v40017-1.job.xml` on board `rm10_40017-1`.
7. Afterwards, on the PC:

   ```
   ls -lt /home/micke/hs_err_pid*.log /home/micke/openpnp/hs_err_pid*.log /tmp/hs_err_pid*.log 2>/dev/null
   ```

   No file, and a job that finished, is the result we want. If a file appears, keep it - it names the JVM and
   the frame that faulted, which is the single most useful artefact still missing from `PRE-CHECK.md` §7.

## What each outcome means

- Clean run, no `hs_err`: Temurin 25 is good enough to build the pre-check on, and the switch stays.
- Clean except a library warning that is not OpenCV: fine, note it here and move on.
- A library that will not load on 25: roll back, and the decision becomes "stay on 11 and treat the corruption
  as a 11-vs-21 question" rather than "move to 25" - `PRE-CHECK.md` §7 gets rewritten accordingly, not patched.
- `free(): ... corrupted` / `SIGSEGV` on 25 too: the JVM was never the cause. Then §7 is wrong about the
  mitigation and the only way forward is ASAN, which means building OpenCV 4.5.5 from source.

## Rollback

```
cp ~/openpnp/OpenPnP.bak-2026-09-29 ~/openpnp/OpenPnP
cp ~/openpnp/OpenPnP.vmoptions.bak-2026-09-29 ~/openpnp/OpenPnP.vmoptions
```

That returns the machine to exactly what it ran before: `openjdk-bin-11`, no native-access flag.
