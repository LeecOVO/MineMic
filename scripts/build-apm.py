#!/usr/bin/env python3
"""Build pinned standalone WebRTC APM and the MineMic C ABI; no runtime downloads."""
import argparse
import hashlib
import json
import os
import platform
import time
from contextlib import contextmanager
from pathlib import Path
import subprocess
import sys
import tarfile
import zipfile

ROOT = Path(__file__).resolve().parents[1]
BUILD = ROOT / 'native/build'
DEPS = BUILD / 'deps'
APM = 'webrtc-audio-processing-2.1'
ABSL = 'abseil-cpp-20240722.0'
RNNOISE = 'rnnoise-0.2'
FILES = [
    (APM+'.tar.xz', 'https://freedesktop.org/software/pulseaudio/webrtc-audio-processing/'+APM+'.tar.xz', 'ae9302824b2038d394f10213cab05312c564a038434269f11dbf68f511f9f9fe'),
    (ABSL+'.tar.gz', 'https://github.com/abseil/abseil-cpp/releases/download/20240722.0/'+ABSL+'.tar.gz', 'f50e5ac311a81382da7fa75b97310e4b9006474f9560ac46f54a9967f07d4ae3'),
    ('absl-meson.zip', 'https://wrapdb.mesonbuild.com/v2/abseil-cpp_20240722.0-3/get_patch', '12dd8df1488a314c53e3751abd2750cf233b830651d168b6a9f15e7d0cf71f7b'),
    (RNNOISE+'.tar.gz', 'https://github.com/xiph/rnnoise/releases/download/v0.2/'+RNNOISE+'.tar.gz', '90fce4b00b9ff24c08dbfe31b82ffd43bae383d85c5535676d28b0a2b11c0d37'),
]

def run(args, **kw):
    print('+', ' '.join(map(str,args)), flush=True)
    return subprocess.run(list(map(str,args)), check=True, **kw)

def query(*args):
    return subprocess.check_output(args, text=True).strip()

@contextmanager
def prepare_lock(path):
    with path.open('a+b') as lock:
        if os.name == 'nt':
            import msvcrt
            if path.stat().st_size == 0: lock.write(b'0'); lock.flush()
            while True:
                try:
                    lock.seek(0); msvcrt.locking(lock.fileno(), msvcrt.LK_NBLCK, 1); break
                except OSError: time.sleep(0.2)
            try: yield
            finally: lock.seek(0); msvcrt.locking(lock.fileno(), msvcrt.LK_UNLCK, 1)
        else:
            import fcntl
            fcntl.flock(lock, fcntl.LOCK_EX)
            try: yield
            finally: fcntl.flock(lock, fcntl.LOCK_UN)

def prepare():
    DEPS.mkdir(parents=True, exist_ok=True)
    with prepare_lock(DEPS/'prepare.lock'):
        for name,url,digest in FILES:
            path = DEPS/name
            if not path.exists():
                temp=path.with_suffix(path.suffix+'.download')
                run(['curl','-fL','--retry','2',url,'-o',temp])
                temp.rename(path)
            if hashlib.sha256(path.read_bytes()).hexdigest()!=digest:
                raise RuntimeError('Dependency checksum mismatch: '+str(path))
        source=DEPS/APM
        if not source.exists():
            with tarfile.open(DEPS/FILES[0][0]) as archive: archive.extractall(DEPS)
        absl=source/'subprojects'/ABSL
        if not absl.exists():
            with tarfile.open(DEPS/FILES[1][0]) as archive: archive.extractall(source/'subprojects')
        if not (absl/'meson.build').exists():
            with zipfile.ZipFile(DEPS/FILES[2][0]) as archive: archive.extractall(source/'subprojects')
        if not (DEPS/RNNOISE).exists():
            with tarfile.open(DEPS/FILES[3][0]) as archive: archive.extractall(DEPS)
        # RNNoise v0.2 shipped an ARM header from its parent project. These three
        # substitutions mirror upstream's vec_neon.h fix; retain the NEON path.
        neon=DEPS/RNNOISE/'src/vec_neon.h'
        header=neon.read_text()
        fixed=header.replace('#include "os_support.h"', '#include "opus_types.h"\n#include "common.h"').replace('OPUS_CLEAR(out, rows);', 'RNN_CLEAR(out, rows);')
        if header != fixed: neon.write_text(fixed)
        tools=BUILD/'tools'
        toolbin=tools/('Scripts' if os.name=='nt' else 'bin')
        python=toolbin/('python.exe' if os.name=='nt' else 'python3')
        if not python.exists(): run([sys.executable,'-m','venv',tools])
        if not (toolbin/('meson.exe' if os.name=='nt' else 'meson')).exists(): run([python,'-m','pip','install','meson==1.9.1','ninja==1.11.1.4'])
    return source, toolbin

def main(target):
    source, toolbin = prepare()
    if target.startswith('ios'):
        # Static linking drops SDK resources; Xcode copies this companion bundle.
        upstream = source/'subprojects'/ABSL/'PrivacyInfo.xcprivacy'
        bundled = ROOT/'iosApp/iosApp/AbseilPrivacy.bundle/PrivacyInfo.xcprivacy'
        if bundled.read_bytes() != upstream.read_bytes():
            raise RuntimeError('Bundled Abseil privacy manifest does not match the pinned SDK')
    output=BUILD/target; output.mkdir(parents=True,exist_ok=True)
    build=output/'meson'
    env=os.environ.copy();env['PATH']=str(toolbin)+os.pathsep+env.get('PATH','')
    android=target.startswith('android-')
    if android:
        abi=target.removeprefix('android-')
        triples={'arm64-v8a':('aarch64-linux-android','aarch64','armv8'), 'armeabi-v7a':('armv7a-linux-androideabi','arm','armv7'), 'x86_64':('x86_64-linux-android','x86_64','x86_64'), 'x86':('i686-linux-android','x86','i686')}
        triple,family,cpu=triples[abi]
        sdk=Path(os.environ.get('ANDROID_HOME',str(Path.home()/'Library/Android/sdk')))
        ndk=Path(os.environ.get('ANDROID_NDK_HOME',str(sdk/'ndk/28.2.13676358')))
        host={'Darwin':'darwin-x86_64','Linux':'linux-x86_64','Windows':'windows-x86_64'}[platform.system()]
        tool=ndk/'toolchains/llvm/prebuilt'/host/'bin'
        suffix='.exe' if os.name=='nt' else ''
        cc=[str(tool/('clang'+suffix)), '--target='+triple+'24']; cpp=[str(tool/('clang++'+suffix)), '--target='+triple+'24']
        ar=str(tool/('llvm-ar'+suffix));system='android';flags=['-fPIC']
        defines=['-DWEBRTC_ANDROID','-DWEBRTC_LINUX','-DWEBRTC_POSIX']
    else:
        sdkname='iphoneos' if target=='iosArm64' else 'iphonesimulator' if target=='iosSimulatorArm64' else 'macosx'
        sdk=query('xcrun','--sdk',sdkname,'--show-sdk-path')
        triple={'iosArm64':'arm64-apple-ios18.2','iosSimulatorArm64':'arm64-apple-ios18.2-simulator','macos':'arm64-apple-macos13.0'}[target]
        flags=['-target',triple,'-isysroot',sdk]
        cc=[query('xcrun','-f','clang')]+flags; cpp=[query('xcrun','-f','clang++')]+flags
        ar=query('xcrun','-f','ar');system='darwin';family='aarch64';cpu='armv8'
        defines=['-DWEBRTC_MAC','-DWEBRTC_POSIX']+(['-DWEBRTC_IOS'] if target!='macos' else [])
    cross=output/'cross.ini'
    cross.write_text("[binaries]\nc = "+repr(cc)+"\ncpp = "+repr(cpp)+"\nar = "+repr(ar)+"\nstrip = 'true'\n[host_machine]\nsystem = "+repr(system)+"\ncpu_family = "+repr(family)+"\ncpu = "+repr(cpu)+"\nendian = 'little'\n[properties]\nneeds_exe_wrapper = true\n")
    if not (build/'build.ninja').exists():
        run([toolbin/('meson.exe' if os.name=='nt' else 'meson'),'setup',build,source,'--cross-file',cross,'--buildtype=release','--default-library=static','--wrap-mode=nodownload','--force-fallback-for=abseil-cpp','-Dauto_features=disabled','-Dneon='+('enabled' if family in ('arm','aarch64') else 'disabled')], env=env)
    run([toolbin/('ninja.exe' if os.name=='nt' else 'ninja'),'-C',build,'-j',os.environ.get('MINEMIC_NATIVE_JOBS','6')],env=env)
    obj=output/'minemic_apm.o'
    rnnoise=DEPS/RNNOISE
    run(cpp+['-std=c++17','-O2','-fvisibility=hidden','-fPIC']+defines+['-I'+str(ROOT/'native/include'),'-I'+str(source/'webrtc'),'-I'+str(source/'subprojects'/ABSL),'-I'+str(rnnoise/'include'),'-c',ROOT/'native/src/minemic_apm.cpp','-o',obj])
    rnnoise_objects=[]
    for name in ['denoise','rnn','pitch','kiss_fft','celt_lpc','nnet','nnet_default','parse_lpcnet_weights','rnnoise_data','rnnoise_tables']:
        compiled=output/('rnnoise_'+name+'.o')
        run(cc+['-std=c99','-O3','-fvisibility=hidden','-fPIC','-DRNNOISE_BUILD','-I'+str(rnnoise/'include'),'-I'+str(rnnoise/'src'),'-c',rnnoise/'src'/(name+'.c'),'-o',compiled])
        rnnoise_objects.append(compiled)
    # Meson folds all static dependency objects into the APM archive.
    libraries=[build/'webrtc/modules/audio_processing/libwebrtc-audio-processing-2.a']
    combined=output/'libminemic_apm.a'
    if android:
        combined.unlink(missing_ok=True)
        # MRI handles spaces when names are double quoted.
        script='create "'+combined.as_posix()+'"\n'+''.join('addmod "'+p.as_posix()+'"\n' for p in [obj]+rnnoise_objects)+''.join('addlib "'+p.as_posix()+'"\n' for p in libraries)+'save\nend\n'
        run([ar,'-M'],input=script,text=True)
    else:
        run(['xcrun','libtool','-static','-o',combined,obj]+rnnoise_objects+libraries)
    (output/'provenance.json').write_text(json.dumps({'source':APM,'sha256':FILES[0][2],'abseil':ABSL,'rnnoise':RNNOISE,'rnnoise_sha256':FILES[3][2],'target':target},indent=2)+'\n')
    print(combined)

if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--target', required=True, choices=['macos','iosArm64','iosSimulatorArm64','android-arm64-v8a','android-armeabi-v7a','android-x86_64','android-x86'])
    main(parser.parse_args().target)
