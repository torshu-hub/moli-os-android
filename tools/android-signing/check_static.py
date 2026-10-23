"""Cheap PR validation. Never generates a key, installs dependencies or signs an APK."""
import ast
import json
from pathlib import Path
import runpy

base=Path(__file__).resolve().parent
root=base.parents[1]
workflow=root/'.github/workflows/android-beta-sign.yml'
before=workflow.read_bytes()
runpy.run_path(str(base/'render_workflow.py'))
if workflow.read_bytes()!=before:
    raise SystemExit('Manual workflow must match its reviewed inline signing/proof sources')
for source in base.glob('*.py'):
    ast.parse(source.read_text(),filename=str(source))
notebook=json.loads((base/'moli-beta-key.ipynb').read_text())
assert notebook['metadata']['colab']['private_outputs'] is True
for cell in notebook['cells']:
    if cell['cell_type']=='code':
        assert cell['execution_count'] is None and not cell['outputs']
        ast.parse(''.join(cell['source']))
print('Signing preparation: canonical manual workflow, Python and empty private notebook outputs verified; no signing access')
