"""Disposable emulator-only original GBox startup observation; never a functional acceptance."""
import hashlib,json,os,pathlib,re,subprocess,sys,time,xml.etree.ElementTree as ET,zipfile

PACKAGE='com.gbox.android'
PIN='6fcbaafbcb033b9e0cc03a5d6acaa54ca6b6cb06d16ce3c2d0657d7addfba257'
WORDS=('HMS','华为','microG','VIP','会员','登录','同意','允许','Google')

def errors(raw):
    frames=re.findall(r'^\s*at ([A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)+)\([^\n)]*?(?::(\d+))?\)\s*$',raw,re.M)
    return {'javaFatal': 'FATAL EXCEPTION:' in raw,
            'nativeSignals': sorted(set(int(n) for n in re.findall(r'Fatal signal (\d+)',raw))),
            'frames': [name+(':'+line if line else '') for name,line in frames[:32]]}

def selftest():
    value=errors('FATAL EXCEPTION: main\n    at com.example.App.start(secret-canary.java:12)\n')
    assert value['javaFatal'] and value['frames']==['com.example.App.start:12']
    assert 'secret-canary' not in json.dumps(value)
    assert errors('Nothing recorded')['javaFatal'] is False
    print('Oracle metadata parser self-check passed; no device accessed.')

def main(serial,apk,output):
    if not re.fullmatch(r'emulator-\d+',serial):raise ValueError('Explicit emulator only')
    output.mkdir(parents=True,exist_ok=True)
    assert hashlib.file_digest(apk.open('rb'),'sha256').hexdigest()==PIN
    result={'schema':1,'sourceSha256':PIN,'startedAtMs':int(time.time()*1000),
            'observationComplete':False,'functionalAcceptance':False,'hmsInstalledByProbe':False,
            'personalAccountAdded':False,'samples':[]}
    installed=False

    def adb(*args,budget=30,required=True):
        p=subprocess.run(['adb','-s',serial,*args],stdout=subprocess.PIPE,stderr=subprocess.PIPE,timeout=budget)
        if required and p.returncode:raise RuntimeError('ADB observation command failed')
        return p

    try:
        with zipfile.ZipFile(apk) as z:
            result['packagedAbis']=sorted({name.split('/')[1] for name in z.namelist() if name.startswith('lib/') and name.endswith('.so')})
        for prop,key in [('ro.product.cpu.abilist','deviceAbis'),('ro.dalvik.vm.native.bridge','nativeBridge')]:
            value=adb('shell','getprop',prop).stdout.decode().strip()
            result[key]=value if re.fullmatch(r'[A-Za-z0-9_.,-]*',value) else 'unknown'
        tool=pathlib.Path(os.environ['ANDROID_HOME'])/'build-tools/35.0.0/aapt2'
        badging=subprocess.check_output([str(tool),'dump','badging',str(apk)],timeout=30).decode()
        assert re.search(r"^package: name='com\.gbox\.android'",badging,re.M)
        (output/'source-badging.txt').write_text(badging,encoding='utf8')
        install=adb('install','--no-incremental','-r',str(apk),budget=120,required=False)
        (output/'install-result.txt').write_bytes(install.stdout+install.stderr)
        result['installExitStatus']=install.returncode
        result['installed']=installed=install.returncode==0 and b'Success' in install.stdout
        if not installed:
            result['observationComplete']=True
            return
        uid_text=adb('shell','cmd','package','list','packages','-U',PACKAGE).stdout.decode()
        uid=re.search(r'^package:com\.gbox\.android uid:(\d+)\s*$',uid_text,re.M)
        if not uid:raise RuntimeError('Cannot identify original app UID')
        component=adb('shell','cmd','package','resolve-activity','--brief','-a','android.intent.action.MAIN',
                      '-c','android.intent.category.LAUNCHER',PACKAGE).stdout.decode().strip().splitlines()[-1]
        if not re.fullmatch(r'com\.gbox\.android/[A-Za-z0-9_.$]+',component):raise RuntimeError('Unknown original launch component')
        result['launchComponent']=component
        start=adb('shell','am','start','-W','-n',component,required=False)
        result['startExitStatus']=start.returncode
        launch=time.monotonic()
        for number,delay in enumerate((2,10,30),1):
            time.sleep(max(0,launch+delay-time.monotonic()))
            screen=adb('exec-out','screencap','-p').stdout
            if not screen.startswith(b'\x89PNG\r\n\x1a\n'):raise RuntimeError('Invalid original screenshot')
            (output/('original-startup-'+str(number)+'.png')).write_bytes(screen)
            xml=adb('exec-out','uiautomator','dump','/dev/tty').stdout.decode('utf8','replace')
            nodes=ET.fromstring(xml[xml.index('<?xml'):xml.index('</hierarchy>')+len('</hierarchy>')])
            text='\n'.join(n.get('text','') for n in nodes.iter('node'))
            packages={n.get('package','') for n in nodes.iter('node')}
            alive=adb('shell','pidof',PACKAGE,required=False).stdout.decode().strip()
            sample={'afterSeconds':delay,'pidPresent':bool(re.fullmatch(r'\d+(?: \d+)*',alive)),
                    'originalUiPresent':PACKAGE in packages,
                    'systemErrorControls':any(n.get('package')=='android' and n.get('resource-id','') in
                        {'android:id/aerr_close','android:id/aerr_wait','android:id/aerr_restart'} for n in nodes.iter('node')),
                    'fixedTextPresent':{word:word.casefold() in text.casefold() for word in WORDS}}
            raw=adb('logcat','-b','crash','-d','--uid='+uid[1],'-v','brief').stdout.decode('utf8','replace')
            sample.update(errors(raw));result['samples'].append(sample)
        result['observationComplete']=True
    except Exception as failure:
        result['failureType']=type(failure).__name__
        raise RuntimeError('Original startup observation incomplete; retain metadata') from None
    finally:
        if installed:
            try:
                stopped=adb('shell','am','force-stop',PACKAGE,required=False)
                home=adb('shell','input','keyevent','KEYCODE_HOME',required=False)
                result['cleanupComplete']=stopped.returncode==0 and home.returncode==0
            except Exception as cleanup:result['cleanupFailureType']=type(cleanup).__name__
        result['finishedAtMs']=int(time.time()*1000)
        (output/'original-startup-result.json').write_text(json.dumps(result,indent=2)+'\n',encoding='utf8')
        print(json.dumps({'observationComplete':result['observationComplete'],'installed':result.get('installed'),
                          'functionalAcceptance':False,'samples':len(result['samples'])}))

if __name__=='__main__':
    if sys.argv[1:]==['--self-test']:selftest()
    elif len(sys.argv)==4:main(sys.argv[1],pathlib.Path(sys.argv[2]),pathlib.Path(sys.argv[3]))
    else:raise SystemExit('Usage: probe-gbox-oracle.py --self-test | emulator-N APK OUTPUT')
