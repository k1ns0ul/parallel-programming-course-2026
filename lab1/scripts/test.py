import os
from pathlib import Path
import subprocess

root = Path(__file__).resolve().parents[1]
os.chdir(root)
java_home = os.environ.get('JAVA_HOME')
java = str(Path(java_home) / 'bin/java') if java_home else 'java'
subprocess.run(['sh', 'scripts/compile.sh'], check=True)
folder = Path('results/tests')
folder.mkdir(parents=True, exist_ok=True)

def run(name, arguments, broken=False):
    result = subprocess.run([java, '-cp', 'build/classes', *arguments],
                            capture_output=True, text=True, timeout=90)
    text = result.stdout + result.stderr
    (folder / (name + '.txt')).write_text(text + f'\nProcess exit code: {result.returncode}\n')
    print(text, flush=True)
    if not broken:
        result.check_returncode()
    elif result.returncode != 0:
        if ('Final statistics lost or duplicated records' not in text
                and 'Expected consistent snapshots' not in text):
            raise RuntimeError('Broken experiment failed for an unexpected reason')
        print('Expected failure observed in deliberately broken variant.', flush=True)
    else:
        print('No failure observed this run; this does not prove correctness.', flush=True)

run('correctness', ['testing.CollectorCorrectnessTest'])
run('consistency', ['testing.InconsistencyTest'])
for i in range(1, 4):
    run(f'broken-{i}', ['testing.InconsistencyTest', 'broken'], broken=True)
