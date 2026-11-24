"""Package the actual Android Rust extension, preserving upstream metadata and license."""
import base64
import hashlib
import io
from pathlib import Path
import sys
import zipfile

source,library,abi=Path(sys.argv[1]),Path(sys.argv[2]),sys.argv[3]
root=Path(__file__).resolve().parents[1]
tag='cp310-cp310-android_24_'+abi.replace('-','_')
distribution='pydantic_core-2.27.0.dist-info'
files={}
for path in (source/'python/pydantic_core').glob('*'):
    if path.is_file():files['pydantic_core/'+path.name]=path.read_bytes()
files['pydantic_core/_pydantic_core.so']=library.read_bytes()
files[distribution+'/METADATA']=(source/'PKG-INFO').read_bytes()
files[distribution+'/WHEEL']=f'Wheel-Version: 1.0\nGenerator: OAS Android native cross build\nRoot-Is-Purelib: false\nTag: {tag}\n'.encode()
for name in ('LICENSE','LICENSE.txt'):
    if (source/name).exists():files[distribution+'/'+name]=(source/name).read_bytes()
record=[]
for name,data in files.items():
    digest=base64.urlsafe_b64encode(hashlib.sha256(data).digest()).decode().rstrip('=')
    record.append(f'{name},sha256={digest},{len(data)}')
files[distribution+'/RECORD']=('\n'.join(record)+f'\n{distribution}/RECORD,,\n').encode()
wheel=root/'.toolchains/wheels'/f'pydantic_core-2.27.0-0-{tag}.whl'
with zipfile.ZipFile(wheel,'w',zipfile.ZIP_DEFLATED) as archive:
    for name,data in files.items():archive.writestr(name,data)
print('BUILT',wheel,hashlib.sha256(wheel.read_bytes()).hexdigest(),flush=True)
