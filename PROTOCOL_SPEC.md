# Spécification Technique & Rétro-Ingénierie — Feeric Lights (`com.ismart.bt.feericlights.color7`)

Ce document détaille l'intégralité du protocole Bluetooth Low Energy (BLE), du chiffrement **CSRMesh**, des commandes propriétaires (`iSmart`) et des bugs identifiés dans l'application Android d'origine décompilée dans `/home/pmahot/feericlight/feericlight`.

---

## 1. Vue d'ensemble de l'architecture d'origine

L'application originale (« **Feeric Lights & Christmas RGB LED** », package `com.ismart.bt.feericlights.color7`, compilée pour Android 8.0 / API 26) est construite par-dessus le SDK de démonstration **Qualcomm / CSR CSRMesh** (`com.csr.csrmeshdemo` + `com.csr.mesh`).

- **Aucun code natif (`.so`)** : Toute la logique (BLE GATT, construction des paquets CSRMesh, chiffrement AES/HMAC) est écrite en Java pur dans `classes.dex`.
- **Pourquoi l'application ne fonctionne plus sur un Pixel 10 Pro (Android 15/16+)** :
  1. `AndroidManifest.xml` ne déclare que `BLUETOOTH` et `BLUETOOTH_ADMIN`. Il manque `ACCESS_FINE_LOCATION` (requis sur Android 6–11) ainsi que `BLUETOOTH_SCAN` et `BLUETOOTH_CONNECT` (obligatoires au runtime sur Android 12+).
  2. Utilisation de l'API obsolète `BluetoothAdapter.startLeScan()` sans gestion des permissions runtime modernes.

---

## 2. Les 4 bugs majeurs de l'application d'origine (Pourquoi le Bluetooth était « capricieux »)

L'analyse du code Smali de `MainActivity.smali` et `LeBearer.smali` révèle pourquoi l'application d'origine perdait la connexion ou mettait très longtemps à se connecter :

1. **Bug critique de faute de frappe sur l'adresse MAC sauvegardée (`"ble.address"` vs `"ble.address.x"`)** :
   - Lorsqu'un pont BLE (la guirlande) se connecte (`MainActivity$MeshHandler.smali`, ligne 538), l'application sauvegarde son adresse MAC dans `SharedPreferences` sous la clé **`"ble.address"`**.
   - Mais au démarrage, dans `MainActivity.connect()` (`MainActivity.smali`, ligne 1658), elle cherche la clé **`"ble.address.x"`** !
   - **Conséquence** : La clé `"ble.address.x"` vaut toujours `null`. L'application ne se reconnecte **jamais** directement à la guirlande connue et lance systématiquement un scan aveugle de 15 secondes (`setAutoConnectBridge(1, 15000, 0, -90)`).
2. **Seuil RSSI de connexion immédiate trop strict (`-55 dBm`)** :
   - Dans `LeBearer.a(b)` (`LeBearer.smali`, ligne 1275), si le signal RSSI de la guirlande est inférieur à `-55 dBm` (`-0x37`), `LeBearer` refuse de se connecter immédiatement lors de la découverte BLE et attend la fin complète de la fenêtre de scan de 15 secondes avant de trier les appareils et d'ouvrir la connexion GATT !
3. **Bug du bouton Retour (`onBackPressed`)** :
   - Dans `MainActivity.onBackPressed()` (`MainActivity.smali`, ligne 4869), appuyer sur le bouton Retour d'Android appelle directement `mService.disconnectBridge()` sans fermer l'activité ni mettre à jour l'interface, coupant silencieusement le Bluetooth tout en laissant l'écran ouvert.
4. **Initialisation bancale de `mSendDeviceId`** :
   - `mSendDeviceId` est initialisé à `0x10000` (`65536`, adresse invalide) dans le constructeur (`MainActivity.smali`, ligne 237) et ne passe à `0` (Broadcast « All items ») que lorsque le `Spinner` UI déclenche son premier `onItemSelected`.

---

## 3. Couche Transport BLE & GATT (CSRMesh Bridge)

### 3.1. Découverte BLE (Scan)
La guirlande diffuse des paquets d'advertising BLE contenant l'UUID de service 16-bit **CSRMesh** :
- **Service UUID annoncé** : `0000FEF1-0000-1000-8000-00805F9B34FB` (`0xFEF1`)

### 3.2. Table GATT du service `0000FEF1-0000-1000-8000-00805F9B34FB`
Une fois connecté en GATT (`device.connectGatt`), `LeBearer.smali` (lignes 176–227 et `LeBearer$4.smali` ligne 982) inspecte les caractéristiques suivantes dans le service `0xFEF1` :

| Rôle | UUID | Propriétés | Description |
| :--- | :--- | :--- | :--- |
| **MTL Continuation CP** (`b`) | `C4EDC000-9DAF-11E3-8004-00025B000B00` | Write / Notify | Canal index `0` (Mode standard CSRMesh : 20 premiers octets) |
| **MTL Complete CP** (`c`) | `C4EDC000-9DAF-11E3-8003-00025B000B00` | Write / Notify | Canal index `1` (Mode standard CSRMesh : octets restants) |
| **MTL TTL / Legacy 0** (`d`) | `0000D011-D102-11E1-9B23-00025B00A5A5` | Write / Notify | Canal index `0` (Mode alternatif si `0xD011` est présent) |
| **Mesh Bridge / Legacy 1** (`e`) | `0000D012-D102-11E1-9B23-00025B00A5A5` | Write / Notify | Canal index `1` (Mode alternatif si `0xD011` est présent) |
| **CCCD Descriptor** (`f`) | `00002902-0000-1000-8000-00805F9B34FB` | Read / Write | Descripteur d'activation des notifications (`0x01, 0x00`) |

### 3.3. Découpage et envoi d'un paquet MTL (24 octets) sur GATT
Dans CSRMesh, un paquet de commande `setRgb` chiffré et signé fait **24 octets** (15 octets MCP chiffrés + 8 octets HMAC + 1 octet TTL).
Comme la MTU BLE par défaut limite une écriture GATT à **20 octets**, `LeBearer.sendPacket` et `LeBearer.writeNext` (`LeBearer.smali`, lignes 2446–2761) découpent le paquet de 24 octets en **2 écritures GATT successives** :

1. **Cas standard (`x == false`, caractéristiques `C4EDC000-...-8004` et `8003`)** :
   - **Écriture 1** : Les **20 premiers octets** (`packet[0..19]`) sont écrits sur la caractéristique **Index 1** (`C4EDC000-9DAF-11E3-8003-00025B000B00`).
   - **Écriture 2** (dans `onCharacteristicWrite` après succès de l'écriture 1) : Les **4 derniers octets** (`packet[20..23]`) sont écrits sur la caractéristique **Index 0** (`C4EDC000-9DAF-11E3-8004-00025B000B00`).
2. **Cas alternatif (`x == true`, si la caractéristique `0000D011-...` existe sur le service)** :
   - **Écriture 1** : Les 20 premiers octets sur **Index 0** (`0000D011-...`).
   - **Écriture 2** : Les 4 derniers octets sur **Index 1** (`0000D012-...`).

---

## 4. Couche Cryptographique CSRMesh (Clé réseau & Chiffrement)

Tout paquet envoyé à la guirlande est chiffré et authentifié avec une clé réseau dérivée d'une phrase secrète codée en dur dans l'application.

### 4.1. Phrase secrète par défaut
Dans `MainActivity.onConnected()` (`MainActivity.smali`, ligne 1966) :
- **Passphrase réseau** : `"8888"` (`authRequired = false`)
- **TTL par défaut** : `0xFF` (`255`)
- **Adresse cible (`deviceId`)** : `0x0000` (`0` = Broadcast à toutes les guirlandes / groupe « All items »)

### 4.2. Dérivation de la clé réseau `NETKEY` (16 octets)
Définie dans `com.csr.mesh.j.a(String, String, String)` (`j.smali`, lignes 310–344 & 403–480) :
1. Concaténer la passphrase et la constante `"\u0000MCP"` :
   $$\text{input} = \text{UTF8}(\text{"8888"} + \text{"\textbackslash 0MCP"}) = \texttt{38 38 38 38 00 4D 43 50}$$
2. Calculer le hash **SHA-256** (32 octets) de `input`.
3. Prendre les **16 derniers octets** du digest SHA-256 (`digest[16..31]`).
4. **Inverser l'ordre des 16 octets** (`p.a([B)` dans `p.smali`, ligne 409).
   - Valeur exacte pré-calculée de `NETKEY` pour `"8888"` :
     - `SHA256("8888\x00MCP")` = `8b2060e7718d00a0f132f8f54638fad8ee557f613146fd8ed06f94275e3ce625`
     - 16 derniers octets : `ee 55 7f 61 31 46 fd 8e d0 6f 94 27 5e 3c e6 25`
     - Après inversion (`NETKEY`) : **`25 E6 3C 5E 27 94 6F D0 8E FD 46 31 61 7F 55 EE`**

### 4.3. Construction du paquet MCP en clair (15 octets)
Définie dans `LightModelApi.setRgb()` (`LightModelApi.smali`, lignes 434–485) et `e.a(IBII)` (`e.smali`, lignes 711–752) :

| Offset | Taille | Champ | Valeur (Little-Endian) | Description |
| :--- | :--- | :--- | :--- | :--- |
| `0..2` | 3 octets | `SEQ` | Compteur 24-bit aléatoire/incrémental | Numéro de séquence unique (`e.d()`, incrémenté à chaque paquet) |
| `3..4` | 2 octets | `SRC_ID` | `0x00, 0x80` (`0x8000`) | Adresse source du contrôleur (téléphone) |
| `5..6` | 2 octets | `DST_ID` | `0x00, 0x00` (`0x0000`) | Adresse destination (`0x0000` = Broadcast à toute la guirlande) |
| `7` | 1 octet | `OPCODE` | `0x8A` (`-0x76`) | Opcode CSRMesh `LIGHT_MODEL` |
| `8` | 1 octet | `SUB_OP` | `0x02` | Sous-opcode `LIGHT_SET_RGB_NO_ACK` |
| `9` | 1 octet | `LEVEL` | `0xFF` (`255`) | Luminosité CSRMesh (fixée à `0xFF`) |
| `10` | 1 octet | `RED` | **`command`** | **ID de la commande Feeric Lights (`1` à `5`)** |
| `11` | 1 octet | `GREEN` | **`param`** | **Paramètre de la commande Feeric Lights** |
| `12` | 1 octet | `BLUE` | **`0x01`** | **Octet de signature fixe (`1`)** pour une commande `doCommand` |
| `13..14` | 2 octets | `DURATION`| `0x00, 0x00` | Durée de transition couleurs (`0`) |

### 4.4. Chiffrement AES-ECB CTR et Signature HMAC-SHA256 (Paquet final de 24 octets)
Définis dans `a.smali` (lignes 26–163), `j.smali` (lignes 482–607) et `f.smali` (lignes 690–845) :
1. **Bloc IV (16 octets)** :
   - `iv[0..2] = plain[0..2]` (les 3 octets du numéro de séquence `SEQ`)
   - `iv[3] = 0x00`
   - `iv[4..5] = plain[3..4]` (les 2 octets `SRC_ID`, soit `0x00, 0x80`)
   - `iv[6..15] = 0x00` (10 octets à zéro)
2. **Keystream AES** :
   - Chiffrer `iv` (16 octets) avec **AES-128/ECB/NoPadding** en utilisant la clé `NETKEY` (16 octets).
3. **Chiffrement du payload (`plain[5..14]`, soit 10 octets)** :
   - Les 5 premiers octets (`plain[0..4]`, soit `SEQ` + `SRC_ID`) restent **en clair** (`enc[0..4] = plain[0..4]`).
   - Les 10 octets suivants (`plain[5..14]`) sont XORés avec les 10 premiers octets du keystream AES :
     $$\text{enc}[5 + i] = \text{plain}[5 + i] \oplus \text{keystream}[i] \quad \text{pour } i \in [0..9]$$
4. **Calcul du MAC (8 octets)** :
   - Préfixer `enc` (15 octets) par 8 octets à zéro (`0x00 * 8` || `enc` = 23 octets).
   - Calculer le **HMAC-SHA256** de ces 23 octets avec la clé `NETKEY`.
   - Prendre les **8 derniers octets** du digest HMAC-SHA256 (`hmac[24..31]`) et **inverser leur ordre** (`p.a([B)`).
5. **Assemblage du paquet MTL final (24 octets)** :
   - `mtl[0..14]` = `enc[0..14]` (15 octets)
   - `mtl[15..22]` = `mac[0..7]` (8 octets)
   - `mtl[23]` = `0xFF` (1 octet TTL = 255)

---

## 5. Table Complète des Commandes Feeric Lights (`doCommand(command, param)`)

Toutes les fonctionnalités de la guirlande passent par `MainActivity.doCommand(int command, int param)` (`MainActivity.smali`, lignes 3486–3521), qui place `command` dans l'octet `RED`, `param` dans l'octet `GREEN`, et `1` dans l'octet `BLUE`.

### 5.1. Commande `1` : Modes d'animation et Extinction (`FunctionFragment.smali`)
| `command` (RED) | `param` (GREEN) | `BLUE` | Fonction dans l'application | Description du mode |
| :---: | :---: | :---: | :--- | :--- |
| **`1`** | **`1`** | `1` | **Combination** | Enchaîne automatiquement les différents effets lumineux |
| **`1`** | **`2`** | `1` | **Sequential** | Défilement séquentiel des LED |
| **`1`** | **`4`** | `1` | **Twinkle** | Scintillement / clignotement étoilé |
| **`1`** | **`6`** | `1` | **Steady On** | Allumage fixe continu |
| **`1`** | **`7`** | `1` | **OFF** | **Éteint la guirlande** |

> **Note d'exploration matérielle** : Les valeurs `param = 3` et `param = 5` ne sont pas câblées aux boutons de `FunctionFragment.smali` (qui utilise `1, 2, 4, 6, 7`), mais correspondent très probablement sur le contrôleur standard 8-modes des guirlandes de Noël aux effets classiques *Slow Glow*, *Chasing/Flash* ou *Slow Fade* ! Nous pourrons ajouter un mode « Labo / Effets cachés » dans la nouvelle application pour les tester en un clic.

### 5.2. Commande `2` : Luminosité / Vitesse (`BrightnessFragment.smali`)
L'application d'origine propose 4 boutons prédéfinis sur une échelle de `0` à `127` (`0x00` à `0x7F`) :
| `command` (RED) | `param` (GREEN) | `BLUE` | Bouton d'origine | Valeur décimale |
| :---: | :---: | :---: | :--- | :--- |
| **`2`** | **`0x0A`** | `1` | **25 %** | `10 / 127` |
| **`2`** | **`0x32`** | `1` | **50 %** | `50 / 127` |
| **`2`** | **`0x5A`** | `1` | **75 %** | `90 / 127` |
| **`2`** | **`0x7F`** | `1` | **100 %** | `127 / 127` |

> **Amélioration possible** : En plus des 4 paliers rapides (25%, 50%, 75%, 100%), on peut offrir un curseur fluide envoyant n'importe quelle valeur entre `1` et `127`.

### 5.3. Commande `3` : Minuterie / Timer (`TimerFragment.smali`)
| `command` (RED) | `param` (GREEN) | `BLUE` | Mode Timer | Cycle automatique |
| :---: | :---: | :---: | :--- | :--- |
| **`3`** | **`0`** | `1` | **Timer OFF** | Désactive la minuterie |
| **`3`** | **`1`** | `1` | **Timer 1** | **6h ON** / 18h OFF |
| **`3`** | **`2`** | `1` | **Timer 2** | **6h ON** / 6h OFF / **3h ON** / 9h OFF |

### 5.4. Commande `4` : Mode de rampe LED (Fonctionnalité cachée dans `FunctionFragment$2.smali`)
Dans `tab_function_fragment.xml`, le groupe `ledRadio` (`Single`, `Single`, `Multi`) a l'attribut `android:visibility="gone"`, mais son code dans `FunctionFragment$2.smali` (lignes 45–102) est entièrement fonctionnel :
| `command` (RED) | `param` (GREEN) | `BLUE` | Bouton caché | Effet |
| :---: | :---: | :---: | :--- | :--- |
| **`4`** | **`1`** | `1` | `radio1` (Single A) | Canal LED 1 uniquement |
| **`4`** | **`2`** | `1` | `radio2` (Single B) | Canal LED 2 uniquement |
| **`4`** | **`3`** | `1` | `radio3` (Multi) | Tous les canaux LED combinés |

### 5.5. Commande `5` : Sélection des 7 Couleurs (`BrightnessFragment.smali`)
Dans `BrightnessFragment.smali` (lignes 182–288), les boutons `color_1` à `color_7` envoient `command = 5` avec un paramètre de `1` à `7` qui correspond exactement à un masque binaire **3-bit RGB** ($\text{Bit}_2 = \text{Rouge}, \text{Bit}_1 = \text{Vert}, \text{Bit}_0 = \text{Bleu}$) :

| `command` (RED) | `param` (GREEN) | Binaire (`R G B`) | Bouton | Couleur affichée (`color_normal_1..7.png`) |
| :---: | :---: | :---: | :--- | :--- |
| **`5`** | **`1`** | `001` | `color_1` | **Bleu** (`#002EFF`) |
| **`5`** | **`2`** | `010` | `color_2` | **Vert** (`#00A83B`) |
| **`5`** | **`3`** | `011` | `color_3` | **Cyan / Bleu glacier** (`#5CE1E6`) |
| **`5`** | **`4`** | `100` | `color_4` | **Rouge** (`#E60012`) |
| **`5`** | **`5`** | `101` | `color_5` | **Rose / Magenta** (`#E4007F`) |
| **`5`** | **`6`** | `110` | `color_6` | **Jaune / Or** (`#FFF100`) |
| **`5`** | **`7`** | `111` | `color_7` | **Blanc pur** (`#FFFFFF`) |
