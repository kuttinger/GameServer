# Aurora Standalone Server & Account-System

Beitrag zu den Roadmap-Punkten **„Standalone Server (Aurora Server)“** und **„Account & Backend Services“**.

Diese Doku beschreibt, was gebaut wurde, wie die Teile zusammenspielen, wie man es baut, startet und testet, welche Sicherheitsentscheidungen getroffen wurden und was noch offen ist. Alle Änderungen liegen auf dem Branch `feature/standalone-and-accounts` in drei Forks:

| Repo | Fork | Inhalt |
|---|---|---|
| GameServer | https://github.com/kuttinger/GameServer/tree/feature/standalone-and-accounts | neues Modul `account`, Account-Login im Match-Server, Shop/Inventar, Sicherheits-Fixes, Tests |
| Launcher | https://github.com/kuttinger/Launcher/tree/feature/standalone-and-accounts | Account-Login (F2), „Private Lobby hosten“, Übergabe an das Spiel |
| Brick-Force | https://github.com/kuttinger/Brick-Force/tree/feature/standalone-and-accounts | `AuroraConnection` (Client-Seite des Aurora-Protokolls), Interop-Testprogramm |

Alles baut auf eurer bestehenden Architektur auf (Netty, SnowFrame, Paket-Generator, `ILoginHandler`), damit es als Pull Request zurückfließen kann.

---

## 1. Überblick

```
 ┌───────────┐  HTTPS/JSON (Login, Ticket)  ┌──────────────────┐
 │ Launcher  │ ───────────────────────────► │ Account-Service  │  zentral, vom Team betrieben
 └─────┬─────┘                              │ (Modul account)  │  SQLite, Port 18900
       │ startet lokal (Host)               └────────▲─────────┘
       ▼                                             │ Ticket prüfen / Kauf / Loadout
 ┌───────────┐  TCP 18890, Aurora-Protokoll  ┌───────┴──────────┐
 │  Spiel    │ ────────────────────────────► │  Match-Server    │  läuft beim Host (Private Lobby)
 │ (Client)  │                               │  (Modul match)   │  oder dediziert auf einem VPS
 └───────────┘                               └──────────────────┘
```

**Ablauf für einen Spieler:**

1. Im Launcher mit **F2** einloggen oder registrieren. Der Launcher erhält ein Session-Token. Das Token liegt nur im Speicher.
2. Klick auf **START**:
   - Der Launcher holt beim Account-Service ein **Play-Ticket**.
   - Ist „Host a private lobby on this PC“ aktiv, startet er den mitgelieferten Match-Server (`server/match.jar`) als Kindprozess. Der Server läuft mit der Java-Runtime des Launchers und lauscht nur auf `127.0.0.1`.
   - Ticket, Server-Adresse und Port schreibt er in `launcher_data.dat` (`aurora.ticket`, `aurora.server.host`, `aurora.server.port`).
3. Das Spiel liest `launcher_data.dat` und verbindet sich mit dem Server. Es führt den Aurora-Handshake aus und loggt sich mit dem Ticket ein.
4. Der Match-Server prüft das Ticket beim Account-Service und lädt Profil und Inventar. Danach schickt er die originalen Login-Pakete mit echten Werten: Kanal, Spielerinfo, Force Points/Tokens und die Item-Liste (464).
5. Käufe im Shop sowie Ausrüsten und Ablegen laufen über den Match-Server zum Account-Service. Gekaufte Waffen gehören damit dem **Account** und nicht mehr der lokalen `Config\Inventory.json`.

Ohne Login im Launcher startet das Spiel wie bisher im Emulator-Modus. Bestehendes Verhalten bleibt also erhalten.

---

## 2. Account-Service (`GameServer/account`)

Ein eigenständiger Dienst: Java 25, Javalin 6, SQLite (`sqlite-jdbc`). Passwörter werden mit PBKDF2 gehasht, das braucht nur das JDK und keine nativen Bibliotheken.

### Starten

```bash
mvn -pl :account -am package
java -jar account/target/jar/account-service.jar account.properties
```

`account.properties` (alle Werte optional; jeder Wert lässt sich per Umgebungsvariable `AURORA_ACCOUNT_<KEY>` überschreiben, zum Beispiel `AURORA_ACCOUNT_PORT`):

```properties
host=0.0.0.0
port=18900
database.file=data/accounts.db
# Preisliste = die shop.txt des Spiels (itemCode, option, forcepoints, brickpoint, tokens, cashback)
shop.file=data/shop.txt
# Nur fuer offizielle/vertrauenswuerdige Server (Belohnungen), NIE an Spieler-Hosts geben
server.key=
session.hours=720
ticket.hours=6
start.force_points=100000
start.tokens=1000
start.items=wau:EQUIP,wax:EQUIP,wba:EQUIP,wap:EQUIP,aac:EQUIP,aad:EQUIP,s07:EQUIP
```

### Datenmodell

| Tabelle | Inhalt |
|---|---|
| `account` | Benutzername (eindeutig, Groß-/Kleinschreibung egal), PBKDF2-Hash, XP, Force Points, Brick Points, Tokens, Coins, Star Dust, Flag „Inventar importiert“ |
| `token` | **gehashte** Session-Tokens und Play-Tickets (SHA-256), Typ und Ablaufzeit |
| `inventory_item` | Item-Code, Usage (EQUIP/UNEQUIP), Tool-Slot, Menge, Ablaufzeit (-1 = permanent), Upgrade-Flag |
| `purchase_log` | jeder Kauf mit Preis, Währung und Herkunft (Launcher oder Server) |

### REST-API (`/api/v1`)

| Methode & Pfad | Auth | Zweck |
|---|---|---|
| `POST /auth/register` `{username, password}` | – | Account anlegen (3–16 Zeichen `[A-Za-z0-9_-]`, Passwort 8–128 Zeichen) |
| `POST /auth/login` | – | → `{token, expiresAt, account}`, nach 10 Fehlversuchen in 5 Minuten 429 |
| `POST /auth/logout` | Bearer Session | Session beenden |
| `GET /me` | Bearer Session | Profil und Inventar |
| `POST /me/ticket` | Bearer Session | → Play-Ticket für den Spielserver |
| `POST /me/purchase` `{code, option, buyHow}` | Bearer Session | Kauf aus dem Launcher |
| `PUT /me/loadout` `{items:[{seq, usage, toolSlot}]}` | Bearer Session | Ausrüstung ändern |
| `POST /me/import-inventory` | Bearer Session | einmaliger Import der alten `Config\Inventory.json` (nur Item-Codes aus der Shop-Liste) |
| `POST /ticket/verify` | `X-Play-Ticket` | für den Spielserver: Profil und Inventar |
| `POST /ticket/purchase` | `X-Play-Ticket` | Kauf aus dem Spiel (Shop-Paket 121) |
| `PUT /ticket/loadout` | `X-Play-Ticket` | Equip (35) und Unequip (37) aus dem Spiel |
| `POST /trusted/reward` `{accountId, xp, forcePoints}` | `X-Server-Key` | Match-Belohnungen, nur von vertrauenswürdigen Servern |
| `GET /health` | – | Statusprüfung |

**Preise** kommen ausschließlich aus der `shop.txt` (`ShopCatalog`) und werden nie vom Client oder Server übernommen. `buyHow` entspricht `Good.BUY_HOW` (0 = Force Points, 1 = Brick Points, 2 = Tokens). Für `option` gilt dieselbe Regel wie im Emulator: Dauer in Tagen, Werte über 30 bedeuten permanent.

---

## 3. Match-Server (`GameServer/core` + `GameServer/match`)

### Neue Startoptionen (`ServerOptions`)

```bash
java -jar match/target/jar/match-1.0.0.jar \
  --port=18890 \
  --account-url=http://127.0.0.1:18900 \
  --bind=127.0.0.1 \
  --public-host=127.0.0.1 \
  --name=Aurora
```

| Option | Umgebungsvariable | Bedeutung |
|---|---|---|
| `--port` | `AURORA_PORT` | TCP-Port (Standard 18890) |
| `--account-url` | `AURORA_ACCOUNT_URL` | Adresse des Account-Service. **Ohne** diese Option bleibt der bisherige `DevLoginHandler` aktiv. |
| `--bind` | `AURORA_BIND` | Adresse, auf der der Server lauscht (Standard `0.0.0.0`; bei Spieler-Hosts setzt der Launcher `127.0.0.1`) |
| `--public-host` | `AURORA_PUBLIC_HOST` | Adresse, die in der Kanalliste angezeigt wird |
| `--name` | `AURORA_SERVER_NAME` | Kanalname |

### Änderungen im Code

- **`LoginType.ACCOUNT_TICKET` (0x8)** und `ILoginData.AccountTicket`: neuer Login-Typ im Paket `ServerboundAuroraLoginPacket`.
- **`AccountLoginHandler`** (core): prüft das Ticket über `AccountClient` und speichert Profil und Ticket am `BFClient` (`account.profile`, `account.ticket`). Bekannte Schwäche: Die Prüfung blockiert den Netty-Thread bis zu 5 Sekunden (TODO im Code).
- **`AccountClient`** (core): HTTP-Client für den Account-Service (Jackson).
- **`ClientListener_`** (match): Mit Account schickt der Login die echten Werte (Wallet, XP, Item-Liste 464). Ohne Account bleiben die bisherigen Testwerte. Kanal-IP, Port und Name kommen aus den Optionen statt fest eingetragen.
- **`ShopListener_`** (match, neu):
  - Kauf (121): Antwort mit Asset (102) und BuyItem (122). Bei einem Fehler wird `seq = -3` gesendet, wie im Emulator.
  - Equip (35) und Unequip (37): Antwort mit Equip (36) bzw. Unequip (38), an alle im Raum.
  - Alle Aufrufe des Account-Service laufen auf Virtual Threads, nicht im Netzwerk-Thread.
- **`AccountItems`**: Hilfsklasse. Klassen mit `_` am Ende werden beim Build umbenannt und lassen sich deshalb nicht von anderen Klassen referenzieren.

### Gefundene und behobene Fehler im bestehenden Code

1. **RSA nur ein Block:** `Encryption.encrypt` und `decrypt` konnten höchstens 117 Bytes verarbeiten. Ein Login-Paket mit Ticket ist größer. Jetzt wird in Blöcken verschlüsselt: Klartext in Stücken zu `keyBytes − 11`, jedes Stück wird ein Block mit `keyBytes` Bytes.
2. **CRC über den falschen Bereich:** Der `BFClientboundPacketEncoder` berechnete die Prüfsumme über das ganze Backing-Array statt nur über die Paketdaten.
3. **Ungeprüfte Paketgröße:** `BFServerboundPacketDecoder` übernahm die vom Client gesendete Länge ungeprüft (`new byte[size]`, bis 4 GB). Ein einziges manipuliertes Paket konnte den Server beziehungsweise den PC des Hosts lahmlegen. Neu gilt `MAX_PACKET_SIZE = 4 MiB`, größere Pakete trennen die Verbindung.
4. **`.gitignore`:** `test/` schloss jeden Ordner `src/test` aus. Neu wird nur der Ordner im Wurzelverzeichnis ignoriert (`/test/`).

---

## 4. Das Aurora-Protokoll (Referenz für Client-Implementierungen)

```
Frame:  uint32 LE Länge | uint16 LE Msg-ID | uint8 CRC (XOR über den Body) | 8 Byte 0xFF | Body
Typen:  int = int32 LE · string = int32 LE Byte-Länge + UTF-16LE · bytes = int32 LE Länge + Rohdaten

C → S 1004 HandshakeInitialize  bytes modulus, bytes exponent          (Client RSA-1024, big endian)
S → C 1005 HandshakeChallenge   bytes modulus, bytes exponent, bytes challenge (mit Client-Key verschlüsselt)
C → S 1006 HandshakeComplete    bytes challenge                        (mit Server-Key neu verschlüsselt)
S → C 1007 RequestLogin         [Client-Key] int Maske der Login-Typen
C → S 1008 Login                [Server-Key] int major, minor, patch, revision, int loginType, string name, string ticket
S → C 1010 LoggedIn, danach die originalen Login-Pakete (141, 147, 148, 417, 27, 2, 464 …)
S → C 1002 Notification string · 1003 Disconnect string · 1001 Heartbeat
C → S 1000 Heartbeat            bool requestsHeartbeat                 (sonst Timeout nach 5 s)

RSA: PKCS#1 v1.5, in Blöcken (siehe oben)
```

**Wichtig:** Die IDs 1000–1027 benutzt der heutige Client bereits für die Emulator-Erweiterung (`ExtensionOpcodes`: `opConnectedAck = 1000` … `opRequestUserSlotMapFailedReq = 1027`). Die beiden Protokolle überschneiden sich also. Der Client entscheidet deshalb über einen Modus (siehe Abschnitt 6), welches Protokoll gilt.

---

## 5. Launcher

- **Account-Fenster (F2)**, eine ImGui-Oberfläche:
  - Login, Registrieren und Logout, Anzeige von Force Points und Tokens.
  - Option „Host a private lobby on this PC“, sonst das Feld „Server address“.
  - Status des lokalen Servers und Knopf zum Stoppen.
- **`LocalServer`**:
  - Startet `server/match.jar` mit der Java-Runtime des Launchers und wartet, bis der Port antwortet.
  - Leitet die Server-Ausgabe in die Launcher-Konsole (F1).
  - Beendet den Server, wenn der Launcher schließt.
- **Neue Config** `user/account.json`:

```json
{
  "account": { "host": "http://127.0.0.1:18900", "username": "" },
  "server":  { "join_host": "", "port": 18890, "host_locally": true, "public_access": false,
               "jar": "server/match.jar", "name": "Aurora" }
}
```

- **`launcher_data.dat`** hat drei neue NBT-Einträge: `aurora.ticket` (String), `aurora.server.host` (String) und `aurora.server.port` (Int).
- **Installer/Release:** Die gebaute `match-1.0.0.jar` muss als `server/match.jar` neben dem Launcher ausgeliefert werden. Das ist in `setup.iss` noch nicht eingetragen.

---

## 6. Spielclient (`Brick-Force`)

- **`_Emulator/Network/Aurora/AuroraConnection.cs`** (neu, MonoBehaviour, in `Core.Initialize` registriert):
  - Liest `launcher_data.dat`. Wenn ein Ticket und ein Server eingetragen sind, schaltet es den **Aurora-Modus** ein.
  - Wartet auf den Login-Screen und verbindet dann über den bestehenden Weg (`BfServer`/`BfPort` + `OnRoundRobin`).
  - Führt Handshake und Ticket-Login aus (`RSACryptoServiceProvider`, funktioniert auch mit .NET 3.5) und sendet den Heartbeat (1000).
  - Zeigt Notification- und Disconnect-Meldungen an.
- **`ClientExtension.cs`**, solange der Aurora-Modus aktiv ist:
  - Nachrichten 1000–1010 gehen zuerst an `AuroraConnection`.
  - Emulator-Extension-Opcodes werden nicht gesendet.
  - Chunked Buffers sind abgeschaltet, weil ihre IDs mit dem Aurora-Protokoll kollidieren.
- **`Tools/AuroraInterop/AuroraInterop.cs`:** ein Konsolenprogramm mit **identischem** Krypto- und Frame-Code.

**Verifiziert:** Das C#-Programm wurde gegen den laufenden Java-Match-Server und Account-Service getestet. Handshake, RSA in beide Richtungen, Ticket-Login und Empfang der Item-Liste funktionieren:

```
challenge: dMgSaY10HSPKRmOpO0IDd1tkpaioBrme
supported login types: 8
logged in
item list received, count 7
OK
```

**Noch nicht verifiziert:** `AuroraConnection.cs` wurde noch **nicht gegen die Unity-Assemblies kompiliert**. Dafür fehlte bisher die Spielinstallation. Getestet ist also das Protokoll, nicht die Einbindung ins Spiel.

---

## 7. Sicherheit – Schutz für Spieler, die eine Lobby hosten

| Risiko | Maßnahme | Stand |
|---|---|---|
| IP und Port des Hosts aus dem Internet erreichbar | Der lokale Server lauscht standardmäßig nur auf `127.0.0.1` (`--bind`, Launcher-Option `server.public_access=false`). Mitspieler sollen über das Steam-Relay beitreten, dabei bleibt die IP verborgen. Öffentlich erreichbar nur nach bewusstem Opt-in. | ✅ umgesetzt |
| Absturz des Host-PCs durch ein manipuliertes Längenfeld | Paketlimit 4 MiB, getestet in `MatchServerAccountIT` (Paket mit ca. 2 GB angeblicher Länge wird abgewiesen, der Server läuft weiter) | ✅ umgesetzt |
| Verbindungsfluten | bestehender `RateLimiter` pro IP | ✅ vorhanden |
| Host bekommt geheime Schlüssel | Der Spieler-Server bekommt **keinen** `server.key` und keine Passwörter, nur die Adresse des Account-Service | ✅ |
| Passwort des Hosts auf dem PC | Der Launcher speichert kein Passwort, die Session liegt nur im Speicher | ✅ |
| Datenbank-Leak | Passwörter mit PBKDF2 (210 000 Iterationen), Tokens nur als SHA-256 gespeichert | ✅ |
| Brute-Force auf Logins | 10 Fehlversuche pro IP und Name in 5 Minuten, Antwortzeit gleicht auch bei unbekannten Namen | ✅ |

**Bewusste Grenzen, bitte beachten:**

- **Spieler-Server sind nicht vertrauenswürdig.** Ein Host kann in seiner Lobby schummeln, etwa bei Kills und Punkten. Deshalb können Spieler-Server **keine** Belohnungen, keine Währung und keine Items vergeben. Das geht nur mit `server.key` über offizielle Server.
- **Tickets der Mitspieler:** Ein bösartiger Host erhält die Tickets der Spieler in seiner Lobby (6 h gültig). Damit könnte er für *diese* Spieler zu Shop-Preisen einkaufen oder ihre Ausrüstung ändern. Er kann nichts stehlen oder erzeugen. Verbesserungsvorschläge stehen unter „Offene Punkte“.
- **HTTPS:** Der Account-Service spricht selbst nur HTTP. Im Betrieb **muss** er hinter einem TLS-Reverse-Proxy laufen, zum Beispiel Caddy oder nginx mit Let's Encrypt, sonst sind Passwörter mitlesbar.
- **Spielverkehr:** Nach dem Login ist er unverschlüsselt, wie im Original.
- **Raum-Passwörter:** Der neue Server prüft sie noch nicht.

---

## 8. Bauen und Testen

Voraussetzungen: JDK 25, Maven 3.9.

```bash
# GameServer: alle Module + Tests (inkl. End-to-End-Test)
cd GameServer
mvn clean install
```

| Test | Prüft |
|---|---|
| `AccountHttpApiTest` (6 Tests) | Registrierung und Login, falsche Passwörter, Kauf mit Katalogpreisen und fehlendem Geld, Mietdauer, Ticket ≠ Session, Server-Key für Belohnungen, einmaliger Inventar-Import ohne unbekannte Items |
| `MatchServerAccountIT` (2 Tests) | startet die **gepackte** `match.jar` als echten Prozess; der Java-Testclient (`AuroraTestClient`, zugleich Protokoll-Referenz) führt Handshake, Ticket-Login, Item-Liste und Kauf aus; geprüft wird, dass der Kauf im Account gespeichert ist und dass ein manipuliertes Riesenpaket den Server nicht abstürzen lässt |

Ergebnis beim letzten Lauf: **8/8 grün**, `BUILD SUCCESS`.

```bash
# Launcher
cd Launcher && mvn clean package     # target/jar/launcher.jar

# C#-Interop-Test (Windows, Roslyn csc aus den VS Build Tools)
csc -out:AuroraInterop.exe Tools/AuroraInterop/AuroraInterop.cs
AuroraInterop.exe 127.0.0.1 18890 <ticket>
```

**Manueller Gesamttest** (sobald der Client kompiliert ist):

1. Den Account-Service starten.
2. Im Launcher `account.host` setzen, F2 öffnen und registrieren.
3. Die Option „Host a private lobby“ aktiviert lassen und START drücken.
4. Im Spiel prüfen: Inventar entspricht dem Account, ein Kauf im Shop zieht Force Points ab.
5. Spiel neu starten: Das gekaufte Item ist noch da.

---

## 9. Offene Punkte / Vorschläge für Issues

1. **Client kompilieren und im Spiel testen** (`AuroraConnection.cs` gegen Unity-DLLs, MSBuild).
2. **Steam-Proxy im Host-Client:** Steam-P2P-Nachrichten der Mitspieler an `127.0.0.1:18890` weiterleiten. Das ist der Weg, über den Mitspieler sicher beitreten, ohne dass ein Port offen sein muss.
3. **Spielmodi:** TDM und DM vollständig über den neuen Server testen. Die weiteren Modi aus dem Emulator (`Gamemodes/`) portieren.
4. **Slot-Konflikte beim Ausrüsten:** Dafür braucht der Server die Item-Template-Tabelle. Heute löst das der Client.
5. **Tickets härten:** an den Server binden, kürzer gültig machen oder einen Bestätigungsschritt für Käufe einführen.
6. **Async-Login:** Die Ticket-Prüfung soll den Netty-Thread nicht mehr blockieren.
7. **Raum-Passwörter** im Match-Server prüfen.
8. **Belohnungen** (XP/FP nach Matches) über offizielle Server mit `server.key`.
9. **Installer:** `server/match.jar` in `setup.iss` aufnehmen und einen produktiven `account.host` als Standard setzen.
10. **Deployment:** Docker-Compose für Account-Service, Reverse Proxy und dedizierten Match-Server.
11. **Launcher-Absturz untersuchen:** Beim allerersten Testlauf ist die JVM einmal mit `EXCEPTION_ACCESS_VIOLATION` im Thread „Scheduler“ abgestürzt. Das ließ sich danach nicht reproduzieren. Ob das ein bestehendes Problem ist, ist offen.

---

## 10. Lizenz-Hinweis

- GameServer und Launcher stehen unter MIT, der Client unter GPL-3.0. Die neuen Dateien folgen der Lizenz des jeweiligen Repos.
- Die `shop.txt` (Spieldaten) liegt bewusst **nicht** im Repo. Der Account-Service lädt sie aus dem Pfad, der in `shop.file` konfiguriert ist.
