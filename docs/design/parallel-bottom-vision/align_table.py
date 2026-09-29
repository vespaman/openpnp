#!/usr/bin/env python3
"""Per-shot bottom-vision table for one machine log.

Groups the log into one block per analysed shot ([ALIGN-TIMING] ... size check), so the
camera, the detected rect, the accepted offsets and the pipeline time belong to the same shot
even when the two parallel worker threads write their lines back to back.

Usage: align_table.py log.txt [log2.txt ...]
"""

import re
import sys

TS = re.compile(r'^\d{4}-\d\d-\d\d ([0-9:.]+) ')
TIMING = re.compile(r'\[ALIGN-TIMING\] part (\S+) camera (\S+) prepare ([\d.]+) ms process ([\d.]+) ms')
RECT = re.compile(r'result rect \{ \{([-\d.]+), ([-\d.]+)\} (\d+)x(\d+) \* ([-\d.]+) \}')
OFFSETS = re.compile(r'Offsets (accepted|too large) \(([^)]*)\)')
NUM = re.compile(r'-?\d+\.?\d*')
DISP = re.compile(r'(\w+) displacement \((\w+)\)')
SIZE = re.compile(r'Width ([\d.]+), Length ([\d.]+)')
FINAL = re.compile(r'Alignment result: \S+ +\| +X:([-\d.]+) Y:([-\d.]+) C:([-\d.]+) \u0394:([-\d.]+)')
CAMNOZ = re.compile(r'Nozzle (N\d+): returning camera (\S+)')


def analyse(path):
    lines = open(path, errors='replace').read().split('\n')
    cam2noz = {}
    for line in lines:
        m = CAMNOZ.search(line)
        if m:
            cam2noz[m.group(2)] = m.group(1)

    shots = []
    shot = None
    job = 0
    events = []
    for line in lines:
        m = TS.match(line)
        t = m.group(1) if m else None
        if 'Job finished' in line:
            job += 1
            events.append((t, 'job %d finished: %s' % (job, line.split(':', 5)[-1].strip())))
        m = TIMING.search(line)
        if m:
            if shot:
                shot['done'] = True
            shot = {'t': t, 'job': job, 'part': m.group(1), 'cam': m.group(2),
                    'prep': float(m.group(3)), 'proc': float(m.group(4)), 'warn': []}
            shots.append(shot)
            continue
        if shot is None or shot.get('done'):
            continue
        m = RECT.search(line)
        if m:
            shot['rect'] = [float(v) for v in m.groups()]
        m = OFFSETS.search(line)
        if m:
            shot['kind'] = m.group(1)
            shot['off'] = [float(v) for v in NUM.findall(m.group(2))[:4]]
        m = DISP.search(line)
        if m:
            shot['cam'] = m.group(1)
            shot['path'] = m.group(2)
        m = SIZE.search(line)
        if m:
            shot['size'] = [float(v) for v in m.groups()]
        m = FINAL.search(line)
        if m:
            shot['final'] = [float(v) for v in m.groups()]
        if 'uses a pre-captured image' in line:
            shot['shot'] = 'pre-captured'
        elif 'autoSettleAndCapture' in line:
            shot['shot'] = 'settled'
        for needle in ('Parallel alignment failed', 'Bottom vision failed', 'Offsets too large',
                       'Cannot reset', 'no result', 'not moved to the shot location'):
            if needle in line:
                shot['warn'].append(needle)
        if 'size ok' in line or 'size' in line and 'tolerance' in line:
            shot['done'] = True
    return shots, events, cam2noz


def main(paths):
    for path in paths:
        shots, events, cam2noz = analyse(path)
        print('\n=== %s' % path)
        for t, msg in events:
            print('    %s  %s' % (t[:12], msg))
        print('%-3s %-9s %-4s %-5s %-11s %-7s %-7s %-8s %-9s %-7s %-6s %-12s %s'
              % ('job', 'time', 'noz', 'cam', 'rect x,y', 'WxH', 'ang', 'off x,y', 'rot', 'size',
                 'proc', 'path', 'notes'))
        for s in shots:
            noz = s.get('noz') or cam2noz.get(s['cam'], '?')
            if 'rect' not in s:
                print('%-3d %-9s %-4s %-5s %-11s %-7s %-7s %-8s %-9s %-7s %6.1f   %-12s NO DETECTION %s'
                      % (s['job'], s['t'][:12], noz, s['cam'], '', '', '', '', '', '',
                         s['proc'], s.get('path', ''), ','.join(sorted(set(s['warn'])))))
                continue
            x, y, w, h, ang = s['rect']
            off = s.get('off', [0.0, 0.0, 0.0, 0.0])
            size = s.get('size', [0.0, 0.0])
            dev = min(abs(ang % 90.0), 90.0 - abs(ang % 90.0))
            note = ','.join(sorted(set(s['warn'])))
            if dev > 6:
                note = 'ROTATED %.1f deg off axis' % min(dev, 90 - dev) + ' ' + note
            print('%-3d %-9s %-4s %-5s %5.1f %5.1f   %2dx%-3d %7.2f  %6.3f %6.3f %8.3f  %.2fx%.2f %6.1f   %-12s %s'
                  % (s['job'], s['t'][:12], noz, s['cam'], x, y, w, h, ang, off[0], off[1], off[3],
                     size[0], size[1], s['proc'], s.get('path', s.get('shot', '')), note))


if __name__ == '__main__':
    main(sys.argv[1:])
