"""Cloud Linux static-analysis runner. Original binaries/code leave CI only as CMS ciphertext."""
import hashlib,json,os,pathlib,re,signal,stat,subprocess,sys,tempfile,urllib.request,zipfile
APK_URL="https://files.gboxlab.com/GBox-1.8.3.32-1080332-7848795-website.apk"
APK_SHA="6fcbaafbcb033b9e0cc03a5d6acaa54ca6b6cb06d16ce3c2d0657d7addfba257"
SO_SHA="daf9e3844d5d6c750dcd360745d3595621719c0809c8b93fb8b665799c81ac3a"
GHIDRA_URL="https://github.com/NationalSecurityAgency/ghidra/releases/download/Ghidra_12.1.4_build/ghidra_12.1.4_PUBLIC_20260921.zip"
GHIDRA_SHA="ddac49f903da9d5bac833e5cc79395098b9c33cfd3279be5f31bd00387d2d4db"
def sha(p):
    with p.open("rb") as f:return hashlib.file_digest(f,"sha256").hexdigest()
def download(url,p,expected,maximum):
    with urllib.request.urlopen(url,timeout=90) as r,p.open("xb") as f:
        total=0
        while block:=r.read(1024*1024):
            total+=len(block)
            if total>maximum:raise RuntimeError("Download limit exceeded")
            f.write(block)
    if sha(p)!=expected:raise RuntimeError("Download identity mismatch")
def cert_text(value):
    if len(value)>16384 or not re.fullmatch(r"\s*-----BEGIN CERTIFICATE-----\s+[A-Za-z0-9+/=\r\n]+-----END CERTIFICATE-----\s*",value):
        raise ValueError("A single public certificate is required")
    return value.encode("ascii")
def safe_summary(p):
    j=json.loads(p.read_text())
    allowed={"schema","sourceSha256","reaVersion","ghidraVersion","functionalAcceptance","inputExecuted","selection",
             "dossierLimit","completeRequestedInvestigation","selectedDossiers","pendingInternalCallees","closeComplete",
             "failurePhase","elapsedMs"}
    if set(j)-allowed or j.get("sourceSha256")!=SO_SHA:raise ValueError("Unexpected summary schema")
    for r in j.get("selectedDossiers",[]):
        if set(r)!={"evidenceId","depth","hasPseudocode","assemblyCount","directCalleeCount","unresolvedCallCount","bodyAvailable","limitationCount"}:
            raise ValueError("Unexpected dossier summary schema")
        if not re.fullmatch(r"ev_[0-9a-f]{64}",r["evidenceId"]):raise ValueError("Invalid Evidence identity")
    return j
def main():
    if sys.platform!="linux":raise RuntimeError("Cloud Linux required")
    root=pathlib.Path.cwd().resolve()
    work=root/"work"/"native-static"
    work.mkdir(parents=True,exist_ok=False)
    os.chmod(work,0o700)
    private=work/"private";private.mkdir(mode=0o700)
    evidence=private/"evidence";evidence.mkdir(mode=0o700)
    out=root/"work"/"native-public";out.mkdir(exist_ok=False)
    cert=private/"recipient.pem";cert.write_bytes(cert_text(os.environ["PUBLIC_RECIPIENT_CERTIFICATE"]))
    phase="recipient-validation";failure=None;summary=None;counter=0
    def command(argv,timeout=180,env=None):
        nonlocal counter
        counter+=1
        with (evidence/f"command-{counter}.log").open("xb") as log:
            p=subprocess.Popen(argv,stdout=log,stderr=subprocess.STDOUT,env=env,start_new_session=True)
            try:code=p.wait(timeout)
            except subprocess.TimeoutExpired:
                os.killpg(p.pid,signal.SIGTERM)
                try:p.wait(5)
                except subprocess.TimeoutExpired:os.killpg(p.pid,signal.SIGKILL);p.wait(5)
                raise RuntimeError("Command timed out") from None
            if code:raise RuntimeError("Command failed; details encrypted")
    try:
        command(["openssl","x509","-in",str(cert),"-noout","-checkend","0"])
        phase="source-acquisition"
        apk=private/"original.apk";download(APK_URL,apk,APK_SHA,300*1024*1024)
        so=private/"libvlitecore64.so"
        with zipfile.ZipFile(apk) as z:
            info=z.getinfo("lib/arm64-v8a/libvlitecore64.so")
            if info.file_size!=915888:raise RuntimeError("Unexpected native source size")
            so.write_bytes(z.read(info))
        if sha(so)!=SO_SHA or so.read_bytes()[:6]!=b"\x7fELF\x02\x01" or int.from_bytes(so.read_bytes()[18:20],"little")!=183:
            raise RuntimeError("Expected pinned AArch64 ELF source")
        phase="tools"
        ghidra_zip=private/"ghidra.zip";download(GHIDRA_URL,ghidra_zip,GHIDRA_SHA,600*1024*1024)
        tools=work/"tools";tools.mkdir()
        with zipfile.ZipFile(ghidra_zip) as z:
            for info in z.infolist():
                target=(tools/info.filename).resolve()
                if not target.is_relative_to(tools.resolve()) or "\\" in info.filename or stat.S_ISLNK(info.external_attr>>16):raise RuntimeError("Unsafe tool archive path")
            z.extractall(tools)
            for info in z.infolist():
                if not info.is_dir():
                    (tools/info.filename).chmod(0o700 if (info.external_attr>>16)&0o111 else 0o600)
        # GitHub ZIP extraction via Python does not preserve executable mode.
        ghidra=tools/"ghidra_12.1.4_PUBLIC"
        for name in ("support/analyzeHeadless","support/launch.sh"):
            (ghidra/name).chmod(0o700)
        rea=tools/"rea"
        command(["npm","install","--prefix",str(rea),"--ignore-scripts","--omit=optional","--no-audit","--no-fund","rea-agents@6.7.0"],360)
        env={**os.environ,"GHIDRA_INSTALL_DIR":str(ghidra),"GHIDRA_HEADLESS_MAXMEM":"2G",
             "REA_GHIDRA_STARTUP_TIMEOUT_MS":"900000","TMPDIR":str(private/"tmp")}
        pathlib.Path(env["TMPDIR"]).mkdir()
        # The public cert is not needed by the analysis child.
        env.pop("PUBLIC_RECIPIENT_CERTIFICATE",None)
        phase="rea-static-analysis"
        command(["node","scripts/analyze-original-native-cloud.mjs",str(so),str(evidence/"rea"),str(rea/"node_modules/rea-agents")],1500,env)
        phase="source-recheck"
        if sha(so)!=SO_SHA or sha(apk)!=APK_SHA:raise RuntimeError("Source changed")
    except Exception as e:
        failure=phase
        (evidence/"runner-error.json").write_text(json.dumps({"phase":phase,"type":type(e).__name__,"message":str(e)}))
    finally:
        if (evidence/"rea/public-summary.json").is_file():
            try:summary=safe_summary(evidence/"rea/public-summary.json")
            except Exception:failure=failure or "summary-validation"
        (evidence/"source-identity.json").write_text(json.dumps({"apkSha256":APK_SHA,"soSha256":SO_SHA,
            "ghidraSha256":GHIDRA_SHA,"sourceRecheckPassed":failure is None}))
        archive=private/"evidence.zip"
        with zipfile.ZipFile(archive,"x",compression=zipfile.ZIP_DEFLATED) as z:
            for p in sorted(evidence.rglob("*")):
                if p.is_file() and not p.is_symlink():z.write(p,p.relative_to(evidence))
        encrypted=out/"original-native-evidence.cms"
        command(["openssl","cms","-encrypt","-binary","-aes-256-gcm","-outform","DER","-in",str(archive),"-out",str(encrypted),str(cert)],90)
        if not encrypted.stat().st_size:raise RuntimeError("No encrypted evidence")
        report={"schema":1,"sourceApkSha256":APK_SHA,"sourceSoSha256":SO_SHA,
            "encryptedSha256":sha(encrypted),"encryptedBytes":encrypted.stat().st_size,
            "analysisComplete":failure is None,"failurePhase":failure,"functionalAcceptance":False,
            "fullNativeParityEstablished":False,"summary":summary}
        (out/"result.json").write_text(json.dumps(report,indent=2)+"\n")
        print(json.dumps(report,sort_keys=True))
    return 1 if failure else 0
if __name__=="__main__":
    if "--self-test" in sys.argv:
        assert cert_text("-----BEGIN CERTIFICATE-----\nYQ==\n-----END CERTIFICATE-----")
        for bad in ("PRIVATE KEY","-----BEGIN CERTIFICATE-----\na!\n-----END CERTIFICATE-----"):
            try:cert_text(bad);raise AssertionError("Invalid certificate accepted")
            except ValueError:pass
        with tempfile.TemporaryDirectory() as d:
            p=pathlib.Path(d)/"summary.json"
            p.write_text(json.dumps({"sourceSha256":SO_SHA,"selectedDossiers":[],"unexpected":"PRIVATE_CANARY"}))
            try:safe_summary(p);raise AssertionError("Unexpected public field accepted")
            except ValueError:pass
        print("certificate and public-summary validation self-checks passed")
    else:sys.exit(main())
