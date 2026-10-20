import csv
import json
from pathlib import Path
import re
import matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt

root = Path(__file__).resolve().parents[1]
rows = list(csv.DictReader((root / 'results/benchmark/medians.csv').open()))
values = {(r['collector'], int(r['threads'])): float(r['ops_per_second']) / 1e6 for r in rows}
names = {'single':'Однопоточный baseline', 'empty':'Пустой лок', 'synchronized':'Общий лок',
         'striped':'16 групп блокировок', 'threadlocal':'ThreadLocal', 'double':'Двойная буферизация'}
fig, axes = plt.subplots(1, 2, figsize=(13, 5))
for name in names:
    if name == 'single':
        for ax in axes:
            ax.axhline(values[(name, 1)], color='gray', linestyle='--', label=names[name])
        continue
    points = sorted((t, v) for (n, t), v in values.items() if n == name)
    for ax in axes:
        ax.plot([p[0] for p in points], [p[1] for p in points], marker='o', label=names[name])
for ax in axes:
    ax.set_xlabel('Рабочие потоки')
    ax.set_ylabel('Млн вызовов record() / с')
    ax.set_xticks([1, 2, 4, 8])
    ax.grid(True, alpha=.25)
    ax.legend(fontsize=8)
axes[0].set_title('Пропускная способность')
axes[0].set_ylim(bottom=0)
axes[1].set_title('Те же данные, логарифмическая шкала Y')
axes[1].set_yscale('log')
fig.suptitle('Apple M5 · Java 26.0.1 · медиана 5 × 5 с после прогрева 5 с')
fig.tight_layout()
fig.savefig(root / 'results/throughput.png', dpi=180)
plt.close(fig)

def parse(text):
    blocks = {}
    for part in re.split(r'(?m)^(?=[A-Za-z]+Collector\s*$)', text):
        lines = part.strip().splitlines()
        if not lines or not lines[0].endswith('Collector'):
            continue
        fields = {}
        for line in lines[1:]:
            if ' = ' in line:
                k,v = line.split(' = ', 1)
                fields[k] = v
        blocks[lines[0]] = fields
    return blocks

normal = parse((root / 'results/tests/consistency.txt').read_text())
broken = [parse((root / f'results/tests/broken-{i}.txt').read_text())['BrokenDoubleBufferedCollector'] for i in range(1,4)]
table = '| Реализация | 1 поток | 2 потока | 4 потока | 8 потоков | 16 потоков |\n|---|---:|---:|---:|---:|---:|\n'
table_names = {'single': '**Этап 0:** Однопоточный baseline', 'empty': '**Этап 1:** Пустой лок (без записи)', 'synchronized': '**Этап 1:** Единый общий лок', 'striped': '**Этап 2:** Шардированный лок (16 групп)', 'threadlocal': '**Этап 3:** Thread-Local состояние (локальные данные)', 'double': '**Этап 4:** Двойная буферизация'}
for name,label in table_names.items():
    table += '| '+label+' | '+' | '.join(f'{values[(name,t)]:.2f}' if (name,t) in values else '—' for t in [1,2,4,8,16])+' |\n'
stress = '| Реализация | Несогласованные | Σбакетов < count | Σбакетов > count | Итоговый count − вызовы | Итоговые бакеты − вызовы |\n|---|---:|---:|---:|---:|---:|\n'
for label,f in [*normal.items(), *[(f'Без проверки active, запуск {i+1}', b) for i,b in enumerate(broken)]]:
    difference=int(f['final sum(buckets)'])-int(f['completed record calls'])
    stress += f"| {label} | {f['inconsistent']} | {f['sum(buckets) < count']} | {f['sum(buckets) > count']} | {f['final count - calls']} | {difference} |\n"
hw=json.loads((root/'results/environment.json').read_text())
ratio=values[('threadlocal',8)]/values[('threadlocal',4)]
double_ratio=values[('double',8)]/values[('threadlocal',8)]
answers = '| Вопрос | Ваш ответ |\n| :--- | :--- |\n| **1. Пустой лок против полного:** Почему пустой лок тоже резко падает с ростом потоков? Что добавляет полная версия? | Пустой метод сохраняет конкуренцию за один монитор, затраты на согласование доступа и возможное ожидание потоков. Полная версия дополнительно изменяет общие данные и дольше удерживает монитор; на 8 потоках получено 27,17 против 19,67 млн операций/с. |\n| **2. Провал шардирования и атомиков:** Почему 16 шардов и атомарная сводка на этапе 2 не спасли скорость записи на законе Ципфа? Куда переехало узкое место? | Неравномерность Ципфа концентрирует обращения в малых бакетах, а общие атомарные count и sum обновляются при каждом вызове. Поэтому после разделения блокировок сохраняется конкуренция за общую сводку: на 8 потоках получено 12,96 млн операций/с. |\n| **3. Замедление на этапе 3:** Почему на этапе 3 рост скорости замедляется после 8 потоков? Как это связано с выводом `lscpu`? | На моём Apple M5 4 производительных и 6 эффективных ядер; характеристики получены через sysctl, поскольку стенд работает на macOS. На 4 → 8 потоках скорость выросла с 501,78 до 1163,21 млн операций/с, поэтому замедление после 8 этим экспериментом не установлено: 16 потоков превышают аппаратный лимит, а размещение потоков планировщиком не измерялось. |\n| **4. Двойная буферизация против TLS:** Почему этап 4 медленнее этапа 3 (в C++ в 2,5–3,5 раза, в Java в 1,1–1,7 раза, а на 16 потоках Java почти догоняет этап 3), но масштабируется почти линейно? В чём разница инструкций при записи? | На моём стенде двойная буферизация оказалась быстрее TLS: 1708,88 против 1163,21 млн операций/с на 8 потоках. Она добавляет volatile-согласование через active и inside, но внутри буферов использует обычные поля вместо атомарных полей TLS; точные машинные инструкции не исследовались, и пример соотношения скоростей для x86 нельзя считать гарантией для ARM64. |\n| **5. Эксперимент с шагом 3:** Что сломалось в тесте на этапе 4, когда вы убрали повторную проверку `active == b`? Почему итоговый `count` расходится с числом вызовов? | Без повторной проверки писатель может выбрать старый буфер, задержаться и начать изменять его после того, как читатель разрешил себе сбор и очистку. В трёх запусках доли несогласованных снимков составили 97,08%, 95,66% и 98,40%, а итоговый count превысил число вызовов на 103, 20 и 6 соответственно. |\n'
results=f'''Вместо отдельного отчёта результаты внесены в таблицы ниже.

#### 1. Характеристики тестового стенда

- CPU: {hw['cpu']}, 4 производительных + 6 эффективных ядер; 10 физических и логических процессоров.
- ОЗУ: 16 GiB. Архитектура: {hw['architecture']}.
- ОС: {hw['os'].replace(chr(10), '; ')}.
- JVM: OpenJDK 26.0.1 Homebrew; исходники компилируются с `--release 17`.
- Метаданные: [environment.json](results/environment.json).

#### 2. Замеры пропускной способности (млн оп/сек)

{table}
16 потоков не запускались: в условии запрещено превышать число аппаратных потоков CPU. Прочерк baseline означает неприменимость, а не нулевую скорость.

![График](results/throughput.png)

[CSV медиан](results/benchmark/medians.csv), [исходные выводы всех прогонов](results/benchmark/).

#### 3. Результаты стресс-теста согласованности снимков

Четыре писателя, 10 000 снимков; финальная проверка после остановки писателей и `join()`.

{stress}
Проценты относятся к конкретным запускам и не являются вероятностями, гарантированными реализацией. Количество операций в стресс-тесте не используется для сравнения скорости. Несогласованность striped/TLS при записи ожидается; после join их итог должен быть точным. Ошибочная реализация проверяется отдельно и может завершаться с AssertionError; это результат эксперимента, а не падение корректных реализаций.

#### 4. Короткие ответы на вопросы (1–2 предложения прямо в таблице)

{answers}
'''
assignment=root/'README.md'
s=assignment.read_text()
marker='### Этап 5. Результаты и выводы (заполнить здесь)'
assert marker in s
commands = '## Запуск решения\n\nТребуется JDK 17+. Команды выполняются из каталога `lab1`:\n\n```sh\nsh scripts/compile.sh\njava -cp build/classes Main double 4\njava -cp build/classes testing.CollectorCorrectnessTest\njava -cp build/classes testing.InconsistencyTest\njava -cp build/classes testing.InconsistencyTest broken\n```\n\nВарианты Main: `single`, `empty`, `synchronized`, `striped`, `threadlocal`, `double`; второй аргумент — количество потоков. Для `single` допускается только один поток. Режим теста `broken` намеренно проверяет ошибочную реализацию и может завершиться с AssertionError.\n\nПолная серия: `python3 scripts/benchmark.py`, затем `python3 scripts/test.py` и `python3 scripts/report.py` (для графика нужен matplotlib). При необходимости задайте `JAVA_HOME`. Каждая точка измеряется в отдельной JVM: прогрев 5 с, пять прогонов по 5 с, медиана. У каждого потока свой счётчик, старт по защёлке, остановка общим флагом; данные генерируются заранее. Старые измерения с nanoTime на каждой записи в таблицу не включены.\n\n---\n\n'
assignment.write_text(s.split(marker)[0]+marker+'\n\n'+results+'\n'+commands)
print('Updated original README.md and throughput.png')
