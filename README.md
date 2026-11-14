# Feeric Lights Modern (Android & Windows CSRMesh Controller)

Contrôleur open-source moderne (**Android** + **Windows PC**) pour les guirlandes LED RGB Bluetooth **Feeric Lights** basées sur le protocole **CSRMesh** (puce CSR101x, service GATT `0xFEF1`).

Ce projet remplace l'application Android abandonnée de 2016 avec :
- **Association automatique CSRMesh MASP (ECDH NIST P-192)** après réinitialisation usine (double-clic sur le boîtier).
- **Chiffrement CSRMesh MCP complet** (AES-128 CTR + HMAC-SHA256, clé réseau `"8888"`).
- **Contrôle complet** : 7 couleurs matérielles 3-bit RGB, modes d'animation intégrés, luminosité continue (`1..127`), minuterie matérielle (`6h/18h`, `6h/6h/3h/9h`), sélection des rampes LED (`A`, `B`, `Multi-Rampes`).
- **Effets dynamiques logiciels** : Mode **Arc-en-Ciel Fluide**, **Aurore Boréale**, **Feu de Cheminée**, **Stroboscope Party**.

---

## Contenu du dépôt

- [`android-app/`](./android-app) — Application Android moderne (Kotlin, Jetpack Compose, Android 8–16+, permissions `neverForLocation`, reconnexion rapide par adresse MAC).
- [`windows-app/FeericLightStudio.cs`](./windows-app/FeericLightStudio.cs) — Application de bureau Windows native (WinForms + WinRT Bluetooth LE) sans installation requise.
- [`PROTOCOL_SPEC.md`](./PROTOCOL_SPEC.md) — Spécification complète du protocole CSRMesh et des opcodes `doCommand(cmd, param)` rétro-conçus.

---

## Téléchargements (GitHub Releases)

Chaque release automatique (via **Release Please** + **GitHub Actions**) fournit directement les binaires prêts à l'emploi :
- `feericlight-modern-vX.Y.Z.apk` (Android)
- `FeericLightStudio-vX.Y.Z.exe` (Windows 10/11 x64)

---

## Compilation locale

### Android
```bash
cd android-app
gradle test assembleDebug assembleRelease
```

### Windows (.NET Framework 4.x + WinRT)
```powershell
C:\Windows\Microsoft.NET\Framework64\v4.0.30319\csc.exe /nologo /target:winexe /out:FeericLightStudio.exe `
  /r:C:\Windows\Microsoft.NET\Framework64\v4.0.30319\System.Windows.Forms.dll `
  /r:C:\Windows\Microsoft.NET\Framework64\v4.0.30319\System.Drawing.dll `
  /r:C:\Windows\Microsoft.NET\Framework64\v4.0.30319\System.Runtime.WindowsRuntime.dll `
  /r:C:\Windows\Microsoft.NET\Framework64\v4.0.30319\System.Runtime.dll `
  /r:C:\Windows\Microsoft.NET\Framework64\v4.0.30319\System.Runtime.InteropServices.WindowsRuntime.dll `
  /r:C:\Windows\Microsoft.NET\Framework64\v4.0.30319\System.Numerics.dll `
  /r:C:\Windows\System32\WinMetadata\Windows.Devices.winmd `
  /r:C:\Windows\System32\WinMetadata\Windows.Foundation.winmd `
  /r:C:\Windows\System32\WinMetadata\Windows.Storage.winmd `
  windows-app\FeericLightStudio.cs
```

---

## Licence

Distribué sous licence **MIT** — voir [`LICENSE`](./LICENSE).
