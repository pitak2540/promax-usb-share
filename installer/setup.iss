; BRAND values written by tools/brand.py
#define BRAND_NAME "PROMAX USB Share"
#define BRAND_PUBLISHER "PROMAX"
#define BRAND_FILE "PromaxUsbShare"
#define BRAND_ID "A7D3F2B1-5E64-4C8A-9F10-PROMAXUSBSHARE"
; Windows installer (Inno Setup 6) — names come from the BRAND defines above
; Installs the program, usbip-win2 and the cable drivers in one go.
#ifndef AppVersion
  #define AppVersion "1.0.0"
#endif
#include "payload\args.iss"

[Setup]
AppId={{{#BRAND_ID}}
AppName={#BRAND_NAME}
AppVersion={#AppVersion}
AppPublisher={#BRAND_PUBLISHER}
DefaultDirName={autopf}\{#BRAND_NAME}
DefaultGroupName={#BRAND_NAME}
DisableProgramGroupPage=yes
PrivilegesRequired=admin
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible
SetupIconFile=..\desktop\icon.ico
UninstallDisplayIcon={app}\{#BRAND_FILE}.exe
WizardStyle=modern
OutputDir=..\dist
OutputBaseFilename={#BRAND_FILE}-Setup
Compression=lzma2
SolidCompression=yes

[Tasks]
Name: "usbip"; Description: "usbip-win2 — ไดรเวอร์รับ USB ผ่านเครือข่าย (จำเป็น)"; GroupDescription: "ติดตั้งไดรเวอร์:"
#ifexist "payload\usbipd-win.msi"
Name: "usbipd"; Description: "usbipd-win — ให้คอมเครื่องนี้แชร์ USB ให้เครื่องอื่นได้"; GroupDescription: "ติดตั้งไดรเวอร์:"
#endif
#ifexist "payload\openport2_setup.exe"
Name: "openport"; Description: "Tactrix OpenPort 2.0 (ไดรเวอร์ + J2534)"; GroupDescription: "ติดตั้งไดรเวอร์:"
#endif
#ifexist "payload\scanmatik-setup.exe"
Name: "scanmatik"; Description: "Scanmatik SM2 Pro (ไดรเวอร์ + J2534)"; GroupDescription: "ติดตั้งไดรเวอร์:"
#endif
#ifexist "payload\drivers\ftdi"
Name: "ftdi"; Description: "FTDI USB Serial (สาย K-line / FTDI)"; GroupDescription: "ติดตั้งไดรเวอร์:"
#endif
#ifexist "payload\CH341SER.EXE"
Name: "ch340"; Description: "WCH CH340 / CH341 (CH341A programmer, สาย CH340)"; GroupDescription: "ติดตั้งไดรเวอร์:"
#endif
#ifexist "payload\drivers\cp210x"
Name: "cp210x"; Description: "Silicon Labs CP210x"; GroupDescription: "ติดตั้งไดรเวอร์:"
#endif
Name: "desktopicon"; Description: "สร้างไอคอนบนเดสก์ท็อป"; GroupDescription: "อื่นๆ:"

[Files]
Source: "..\dist\{#BRAND_FILE}\*"; DestDir: "{app}"; Flags: ignoreversion recursesubdirs createallsubdirs
Source: "payload\usbip-win2-setup.exe"; DestDir: "{tmp}"; Flags: deleteafterinstall; Tasks: usbip
#ifexist "payload\usbipd-win.msi"
Source: "payload\usbipd-win.msi"; DestDir: "{tmp}"; Flags: deleteafterinstall; Tasks: usbipd
#endif
#ifexist "payload\openport2_setup.exe"
Source: "payload\openport2_setup.exe"; DestDir: "{tmp}"; Flags: deleteafterinstall; Tasks: openport
#endif
#ifexist "payload\scanmatik-setup.exe"
Source: "payload\scanmatik-setup.exe"; DestDir: "{tmp}"; Flags: deleteafterinstall; Tasks: scanmatik
#endif
#ifexist "payload\drivers\ftdi"
Source: "payload\drivers\ftdi\*"; DestDir: "{tmp}\drivers\ftdi"; Flags: recursesubdirs createallsubdirs deleteafterinstall; Tasks: ftdi
#endif
#ifexist "payload\CH341SER.EXE"
Source: "payload\CH341SER.EXE"; DestDir: "{tmp}"; Flags: deleteafterinstall; Tasks: ch340
#endif
#ifexist "payload\drivers\cp210x"
Source: "payload\drivers\cp210x\*"; DestDir: "{tmp}\drivers\cp210x"; Flags: recursesubdirs createallsubdirs deleteafterinstall; Tasks: cp210x
#endif

[Icons]
Name: "{group}\{#BRAND_NAME}"; Filename: "{app}\{#BRAND_FILE}.exe"
Name: "{autodesktop}\{#BRAND_NAME}"; Filename: "{app}\{#BRAND_FILE}.exe"; Tasks: desktopicon

[Run]
Filename: "{tmp}\usbip-win2-setup.exe"; Parameters: "/VERYSILENT /SUPPRESSMSGBOXES /NORESTART"; StatusMsg: "กำลังติดตั้ง usbip-win2..."; Tasks: usbip; Check: not UsbipInstalled
#ifexist "payload\usbipd-win.msi"
Filename: "{sys}\msiexec.exe"; Parameters: "/i ""{tmp}\usbipd-win.msi"" /qn /norestart"; StatusMsg: "กำลังติดตั้ง usbipd-win..."; Flags: runhidden; Tasks: usbipd; Check: not UsbipdInstalled
#endif
#ifexist "payload\openport2_setup.exe"
Filename: "{tmp}\openport2_setup.exe"; Parameters: "{#OPENPORT_ARGS}"; StatusMsg: "กำลังติดตั้งไดรเวอร์ Tactrix OpenPort 2.0..."; Tasks: openport
#endif
#ifexist "payload\scanmatik-setup.exe"
Filename: "{tmp}\scanmatik-setup.exe"; Parameters: "{#SCANMATIK_ARGS}"; StatusMsg: "กำลังติดตั้งไดรเวอร์ Scanmatik SM2 Pro..."; Tasks: scanmatik
#endif
#ifexist "payload\drivers\ftdi"
Filename: "{sys}\pnputil.exe"; Parameters: "/add-driver ""{tmp}\drivers\ftdi\*.inf"" /subdirs /install"; StatusMsg: "กำลังติดตั้งไดรเวอร์ FTDI..."; Flags: runhidden; Tasks: ftdi
#endif
#ifexist "payload\CH341SER.EXE"
Filename: "{tmp}\CH341SER.EXE"; Parameters: "{#CH341_ARGS}"; StatusMsg: "กำลังติดตั้งไดรเวอร์ CH340/CH341..."; Tasks: ch340
#endif
#ifexist "payload\drivers\cp210x"
Filename: "{sys}\pnputil.exe"; Parameters: "/add-driver ""{tmp}\drivers\cp210x\*.inf"" /subdirs /install"; StatusMsg: "กำลังติดตั้งไดรเวอร์ CP210x..."; Flags: runhidden; Tasks: cp210x
#endif
Filename: "{app}\{#BRAND_FILE}.exe"; Description: "เปิด {#BRAND_NAME}"; Flags: nowait postinstall skipifsilent; Check: not NeedsRestart

[UninstallRun]
; forget saved phones/PCs and pairing codes (license key and trial date are kept)
Filename: "{app}\{#BRAND_FILE}.exe"; Parameters: "--clear-history"; RunOnceId: "ClearHistory"; Flags: waituntilterminated runhidden

[Code]
var
  UsbipWasMissing: Boolean;

function UsbipInstalled(): Boolean;
begin
  Result := FileExists(ExpandConstant('{commonpf64}\USBip\usbip.exe'));
end;

function UsbipdInstalled(): Boolean;
begin
  Result := FileExists(ExpandConstant('{commonpf64}\usbipd-win\usbipd.exe'));
end;

function InitializeSetup(): Boolean;
begin
  UsbipWasMissing := not UsbipInstalled();
  Result := True;
end;

function NeedsRestart(): Boolean;
begin
  Result := UsbipWasMissing and WizardIsTaskSelected('usbip');
end;

function NeedRestart(): Boolean;
begin
  Result := NeedsRestart();
end;
