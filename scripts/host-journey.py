#!/usr/bin/env python3
"""Destructive only to this lab's app data and synthetic fixture. Run on the named lab emulator."""
from pathlib import Path
from fresh_android_xml import read_ui_xml
from common_goods_ui import CommonGoodsUi
from verified_note_input import enter_note
import datetime, hashlib, json, os, re, shutil, subprocess, time, urllib.request, xml.etree.ElementTree as ET
ROOT=Path(__file__).resolve().parents[1]
OUT=Path(os.environ.get('CHAPTER13_EVIDENCE', ROOT/'evidence/host-journeys'/datetime.datetime.now(datetime.timezone.utc).strftime('%Y%m%dT%H%M%SZ')))
OUT.mkdir(parents=True,exist_ok=False)
for helper_name in ('host-journey.py', 'common_goods_ui.py', 'fresh_android_xml.py', 'verified_note_input.py'):
    helper = Path(__file__).with_name(helper_name)
    if helper.is_file():
        (OUT / helper_name).write_bytes(helper.read_bytes())
ADB=Path(os.environ['ANDROID_HOME'])/'platform-tools/adb'
JAVA=Path(os.environ['JAVA_HOME'])/'bin/java'
APP='staff.android.offline'
backend=None; logs=[]; events=[]; server_number=0
apk=OUT/'tested-app-debug.apk'
server_jar=OUT/'tested-server.jar'
shutil.copy2(ROOT/'app/build/outputs/apk/debug/app-debug.apk',apk)
shutil.copytree(ROOT/'server/build/install/server/lib',OUT/'tested-server-libs')
shutil.copy2(OUT/'tested-server-libs/server.jar',server_jar)
artifact_record={'tested_apk_sha256':hashlib.sha256(apk.read_bytes()).hexdigest(),
    'tested_server_jar_sha256':hashlib.sha256(server_jar.read_bytes()).hexdigest(),
    'server_distribution_jars':{p.name:hashlib.sha256(p.read_bytes()).hexdigest()
        for p in sorted((OUT/'tested-server-libs').glob('*.jar'))}}
(OUT/'artifacts.json').write_text(json.dumps(artifact_record,indent=2)+'\n')

def run(*args):
    p=subprocess.run([str(ADB),'-s','emulator-5554',*args],capture_output=True,check=True)
    return p.stdout

def xml():
    return read_ui_xml(run, '/sdcard/chapter13-window.xml', {APP})

ui = CommonGoodsUi(xml, run, OUT)
def capture(name, contains=(), absent=()):
    frames=ui.observe(name)
    content='\n'.join(value for _,value in frames)
    for text in contains: assert text in content, (name,'missing',text)
    for text in absent: assert text not in content, (name,'unexpected',text)
    events.append({'screen':name,'viewports':[label for label,_ in frames],
                   'asserted':list(contains),'absent':list(absent),
                   'scope':'Assertions span retained scrolling viewports; not one simultaneous screenshot'})

def tap(text):ui.tap(text)

def edit(text):
    number=1+sum('inputSetup' in event for event in events)
    events.append(enter_note(ui,run,xml,OUT,f'input-{number:02}',text))

def control(name):
    req=urllib.request.Request('http://127.0.0.1:8788/control/'+name,data=b'',method='POST')
    with urllib.request.urlopen(req,timeout=5) as response: assert response.status==200

def start():
    global backend,server_number
    server_number+=1
    f=(OUT/f'server-{server_number}.log').open('w');logs.append(f)
    backend=subprocess.Popen([str(JAVA),'-cp',str(OUT/'tested-server-libs/*'),
        'staff.android.offline.server.ServerMainKt'],stdout=f,stderr=subprocess.STDOUT)
    for _ in range(100):
        try:
            with urllib.request.urlopen('http://127.0.0.1:8788/control/journal',timeout=.2): return
        except Exception: time.sleep(.1)
    raise RuntimeError('Server did not start')

def stop():
    global backend
    if backend is not None:
        backend.terminate(); backend.wait(timeout=10);backend=None

def launch():
    run('shell','am','start','-n',APP+'/.MainActivity');time.sleep(1)

try:
    start()
    run('reverse','tcp:8788','tcp:8788')
    run('install','-r',str(apk))
    installed=run('shell','pm','path',APP).decode().strip().removeprefix('package:')
    assert installed.startswith('/') and '\n' not in installed
    installed_hash=run('shell','sha256sum',installed).decode().split()[0]
    assert installed_hash==artifact_record['tested_apk_sha256']
    artifact_record.update(installed_path=installed,installed_sha256=installed_hash)
    (OUT/'artifacts.json').write_text(json.dumps(artifact_record,indent=2)+'\n')
    run('shell','pm','clear',APP)
    launch();tap('Account A');tap('Refresh');time.sleep(.8)
    capture('01-base',('Synced','Compare lenses','Server revision 5'))
    stop() # No accepted server advancement yet; restarted fixture will have the same v5 base.
    edit('Check lens mount');tap('Save locally')
    capture('02-offline-save',('Saved note: Check lens mount','Pending'))
    tap('Refresh');time.sleep(5.5)
    capture('03-no-server-read',('Saved note: Check lens mount','Pending','Action unavailable'))
    old_pid=run('shell','pidof',APP).decode().strip()
    run('shell','am','force-stop',APP)
    assert not run('shell','sh','-c',f'pidof {APP} || true').strip()
    launch();tap('Account A');time.sleep(.5)
    new_pid=run('shell','pidof',APP).decode().strip();assert old_pid!=new_pid
    events.append({'forceStop':{'oldPid':old_pid,'newPid':new_pid}})
    capture('04-process-reopen-offline',('Saved note: Check lens mount','Pending','Server revision 5'))
    start();tap('Refresh');time.sleep(.8)
    capture('05-stale-refresh',('Saved note: Check lens mount','Pending','Server revision 5: Compare lenses'))
    tap('Sync once');time.sleep(.8)
    capture('06-accepted',('Saved note: Check lens mount','Synced','Server revision 6'))
    edit('Ask about warranty');tap('Save locally');control('other-device');tap('Sync once');time.sleep(.8)
    capture('07-conflict',('Saved note: Ask about warranty','Conflict','Server revision 7: Check shutter count'))
    tap('Use server')
    capture('08-use-server',('Saved note: Check shutter count','Synced'))
    tap('Save locally');control('other-device');tap('Sync once');time.sleep(.8);tap('Keep mine')
    capture('09-keep-mine-pending',('Saved note: Ask about warranty','Pending','Server revision 8'))
    tap('Sync once');time.sleep(.8)
    capture('10-reconciled',('Saved note: Ask about warranty','Synced','Server revision 9'))
    tap('Sign out · discard local edits');tap('Account B');tap('Refresh');time.sleep(.8)
    capture('11-account-b',('Account B','Saved note: Account B private note'),('Ask about warranty','Check lens mount'))
    edit('Private B changed');tap('Save locally');tap('Sign out · discard local edits');tap('Account A')
    capture('12-account-a-purged',('Account A','No local note.'),('Ask about warranty','Private B changed'))
    with urllib.request.urlopen('http://127.0.0.1:8788/control/journal') as response:
        (OUT/'wire-journal.json').write_bytes(response.read())
    result={'tested_server_jar_sha256':hashlib.sha256(server_jar.read_bytes()).hexdigest(),'tested_apk_sha256':hashlib.sha256(apk.read_bytes()).hexdigest(),'status':'PASS','events':events,'boundaries':['emulator functional evidence, not a timing benchmark',
        'server stopped before any accepted server revision advancement; both fixture starts have revision5',
        'force-stop/new PID verifies process recreation; database-reopen contracts are separate',
        'fixture is synthetic; sign-out intentionally discards unsent local edits'],
        'device':run('shell','getprop','ro.build.fingerprint').decode().strip()}
    (OUT/'result.json').write_text(json.dumps(result,indent=2)+'\n')
    print(json.dumps({'status':'PASS','screens':12,'processPids':[old_pid,new_pid],'evidence':str(OUT)}))
except Exception as failure:
    (OUT/'failure.json').write_text(json.dumps({'status':'FAIL','error':repr(failure),'events':events,**artifact_record},indent=2)+'\n')
    raise
finally:
    stop()
    for f in logs: f.close()
