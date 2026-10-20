import csv
import os
from pathlib import Path
import subprocess

root = Path(__file__).resolve().parents[1]
os.chdir(root)
java_home = os.environ.get('JAVA_HOME')
java = str(Path(java_home) / 'bin/java') if java_home else 'java'
subprocess.run(['sh', 'scripts/compile.sh'], check=True)
folder = Path('results/benchmark')
folder.mkdir(parents=True, exist_ok=True)
threads = [t for t in [1, 2, 4, 8, 16] if t <= (os.cpu_count() or 1)]
with open(folder / 'medians.csv', 'w') as output:
    writer = csv.writer(output)
    writer.writerow(['collector', 'threads', 'ops_per_second'])
    output.flush()
    for name in ['single', 'empty', 'synchronized', 'striped', 'threadlocal', 'double']:
        for count in ([1] if name == 'single' else threads):
            print(f'Start: {name}, threads={count}', flush=True)
            result = subprocess.run([java, '-cp', 'build/classes', 'Main', name, str(count)],
                                    capture_output=True, text=True, timeout=90)
            (folder / f'{name}-{count}.txt').write_text(result.stdout + result.stderr)
            result.check_returncode()
            line = next(line for line in result.stdout.splitlines() if line.startswith('MEDIAN,'))
            _, variant, n, speed = line.split(',')
            writer.writerow([variant, n, speed])
            output.flush()
            print(f'Done: {variant}, threads={n}, {float(speed)/1e6:.3f} Mops/s', flush=True)
