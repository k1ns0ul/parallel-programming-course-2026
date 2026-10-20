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
for name,label in names.items():
    table += '| '+label+' | '+' | '.join(f'{values[(name,t)]:.2f}' if (name,t) in values else '—' for t in [1,2,4,8,16])+' |\n'
stress = '| Реализация | Несогласованные | Σбакетов < count | Σбакетов > count | Итоговый count − вызовы | Итоговые бакеты − вызовы |\n|---|---:|---:|---:|---:|---:|\n'
for label,f in [*normal.items(), *[(f'Без проверки active, запуск {i+1}', b) for i,b in enumerate(broken)]]:
    difference=int(f['final sum(buckets)'])-int(f['completed record calls'])
    stress += f"| {label} | {f['inconsistent']} | {f['sum(buckets) < count']} | {f['sum(buckets) > count']} | {f['final count - calls']} | {difference} |\n"
hw=json.loads((root/'results/environment.json').read_text())
ratio=values[('threadlocal',8)]/values[('threadlocal',4)]
double_ratio=values[('double',8)]/values[('threadlocal',8)]
answers=f'''| Вопрос | Ответ |
|---|---|
| Пустой лок против полного | Потоки конкурируют за один монитор даже при пустом методе: возникают затраты на координацию и возможное ожидание. Полная версия дополнительно изменяет общие данные и увеличивает время удержания монитора; одни замеры не позволяют посчитать переключения контекста или пересылки кэш-линий. |
| Почему 16 групп и атомики не устраняют конкуренцию? | Распределение Ципфа концентрирует записи в малых бакетах, а общие count и sum изменяются каждым писателем. Разделение блокировок массива сохраняет конкуренцию за общую сводку. |
| Масштабирование ThreadLocal и устройство CPU | На данном M5 4 производительных и 6 эффективных ядер, всего 10 аппаратных потоков. Переход 4 → 8 дал коэффициент {ratio:.2f}; восемь одинаково производительных ядер предполагать нельзя. Замера выше 8 в обязательной серии нет, поэтому замедление после 8 не установлено; 16 превышает аппаратный лимит. Планировщик и частоты также влияют на результат. |
| Двойная буферизация против ThreadLocal | На 8 потоках отношение скорости double/TLS равно {double_ratio:.2f}. Двойная буферизация добавляет volatile-чтения active, volatile-запись inside.set и проверку участия, завершая запись через setRelease. При этом внутри буферов используются обычные long и массивы вместо атомарных полей TLS: этот выигрыш может перекрыть стоимость протокола. Указанный в условии порядок скоростей не универсален; утверждения об инструкциях x86 нельзя переносить напрямую на ARM64 M5. |
| Что ломается без повторной проверки active? | Писатель может выбрать старый буфер до переключения и объявить участие после того, как читатель уже разрешил себе его сбор. Запись пересекается с чтением и очисткой, повреждая статистику. Конкретное расхождение count зависит от запуска; совпадение count не исключает повреждения бакетов. |
'''
results=f'''## Результаты

### Стенд

- CPU: {hw['cpu']}, 4 производительных + 6 эффективных ядер; 10 физических и логических процессоров.
- ОЗУ: 16 GiB. Архитектура: {hw['architecture']}.
- ОС: {hw['os'].replace(chr(10), '; ')}.
- JVM: OpenJDK 26.0.1 Homebrew; исходники компилируются с `--release 17`.
- Метаданные: [environment.json](results/environment.json).

### Производительность, млн операций/с

{table}
16 потоков не запускались: в условии запрещено превышать число аппаратных потоков CPU. Прочерк baseline означает неприменимость, а не нулевую скорость.

![График](results/throughput.png)

[CSV медиан](results/benchmark/medians.csv), [исходные выводы всех прогонов](results/benchmark/).

### Согласованность

Четыре писателя, 10 000 снимков; финальная проверка после остановки писателей и `join()`.

{stress}
Проценты относятся к конкретным запускам и не являются вероятностями, гарантированными реализацией. Количество операций в стресс-тесте не используется для сравнения скорости. Несогласованность striped/TLS при записи ожидается; после join их итог должен быть точным. Ошибочная реализация проверяется отдельно и может завершаться с AssertionError; это результат эксперимента, а не падение корректных реализаций.

### Ответы на вопросы задания

{answers}
'''
assignment=root/'README.md'
s=assignment.read_text()
marker='### Этап 5. Результаты и выводы (заполнить здесь)'
assert marker in s
commands = '## Запуск решения\n\nТребуется JDK 17+. Команды выполняются из каталога `lab1`:\n\n```sh\nsh scripts/compile.sh\njava -cp build/classes Main double 4\njava -cp build/classes testing.CollectorCorrectnessTest\njava -cp build/classes testing.InconsistencyTest\njava -cp build/classes testing.InconsistencyTest broken\n```\n\nВарианты Main: `single`, `empty`, `synchronized`, `striped`, `threadlocal`, `double`; второй аргумент — количество потоков. Для `single` допускается только один поток. Режим теста `broken` намеренно проверяет ошибочную реализацию и может завершиться с AssertionError.\n\nПолная серия: `python3 scripts/benchmark.py`, затем `python3 scripts/test.py` и `python3 scripts/report.py` (для графика нужен matplotlib). При необходимости задайте `JAVA_HOME`. Каждая точка измеряется в отдельной JVM: прогрев 5 с, пять прогонов по 5 с, медиана. У каждого потока свой счётчик, старт по защёлке, остановка общим флагом; данные генерируются заранее. Старые измерения с nanoTime на каждой записи в таблицу не включены.\n\n---\n\n'
assignment.write_text(s.split(marker)[0]+marker+'\n\n'+commands+results)
print('Updated original README.md and throughput.png')
