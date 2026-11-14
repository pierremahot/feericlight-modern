using System;
using System.Collections.Concurrent;
using System.Collections.Generic;
using System.Drawing;
using System.Linq;
using System.Numerics;
using System.Security.Cryptography;
using System.Text;
using System.Threading;
using System.Threading.Tasks;
using System.Windows.Forms;
using Windows.Devices.Bluetooth;
using Windows.Devices.Bluetooth.Advertisement;
using Windows.Devices.Bluetooth.GenericAttributeProfile;
using Windows.Foundation;
using Windows.Storage.Streams;
using Point = System.Drawing.Point;
using Size = System.Drawing.Size;

namespace FeericLightStudio
{
    // =========================================================================
    // NIST P-192 Elliptic Curve for CSRMesh MASP Association
    // =========================================================================
    static class P192
    {
        public static readonly BigInteger P = BigInteger.Parse("6277101735386680763835789423207666416083908700390324961279");
        public static readonly BigInteger A = P - 3;
        public static readonly BigInteger B = BigInteger.Parse("0064210519e59c80e70fa7e9ab72243049feb8deecc146b9b1", System.Globalization.NumberStyles.HexNumber);
        public static readonly BigInteger Gx = BigInteger.Parse("00188da80eb03090f67cbf20eb43a18800f4ff0afd82ff1012", System.Globalization.NumberStyles.HexNumber);
        public static readonly BigInteger Gy = BigInteger.Parse("0007192b95ffc8da78631011ed6b24cdd573f977a11e794811", System.Globalization.NumberStyles.HexNumber);

        public static BigInteger Mod(BigInteger x)
        {
            BigInteger r = x % P;
            return r.Sign < 0 ? r + P : r;
        }

        public static BigInteger ModInv(BigInteger a)
        {
            return BigInteger.ModPow(Mod(a), P - 2, P);
        }

        public static Tuple<BigInteger, BigInteger> PointAdd(Tuple<BigInteger, BigInteger> p1, Tuple<BigInteger, BigInteger> p2)
        {
            if (p1 == null) return p2;
            if (p2 == null) return p1;
            BigInteger x1 = p1.Item1, y1 = p1.Item2;
            BigInteger x2 = p2.Item1, y2 = p2.Item2;
            if (x1 == x2)
            {
                if (Mod(y1 + y2) == 0) return null;
                return PointDouble(p1);
            }
            BigInteger lam = Mod((y2 - y1) * ModInv(x2 - x1));
            BigInteger x3 = Mod(lam * lam - x1 - x2);
            BigInteger y3 = Mod(lam * (x1 - x3) - y1);
            return Tuple.Create(x3, y3);
        }

        public static Tuple<BigInteger, BigInteger> PointDouble(Tuple<BigInteger, BigInteger> p)
        {
            if (p == null) return null;
            BigInteger x = p.Item1, y = p.Item2;
            if (y.IsZero) return null;
            BigInteger lam = Mod((3 * x * x + A) * ModInv(2 * y));
            BigInteger x3 = Mod(lam * lam - 2 * x);
            BigInteger y3 = Mod(lam * (x - x3) - y);
            return Tuple.Create(x3, y3);
        }

        public static Tuple<BigInteger, BigInteger> ScalarMult(BigInteger k, Tuple<BigInteger, BigInteger> pt)
        {
            Tuple<BigInteger, BigInteger> res = null;
            Tuple<BigInteger, BigInteger> addend = pt;
            BigInteger n = k;
            while (n > 0)
            {
                if (!n.IsEven) res = PointAdd(res, addend);
                addend = PointDouble(addend);
                n >>= 1;
            }
            return res;
        }

        public static byte[] To24BytesLE(BigInteger v)
        {
            byte[] raw = v.ToByteArray();
            byte[] out24 = new byte[24];
            Array.Copy(raw, 0, out24, 0, Math.Min(24, raw.Length));
            return out24;
        }

        public static BigInteger From24BytesLE(byte[] b24)
        {
            byte[] unsignedBuf = new byte[25];
            Array.Copy(b24, 0, unsignedBuf, 0, 24);
            return new BigInteger(unsignedBuf);
        }
    }

    // =========================================================================
    // Main Windows Studio Form
    // =========================================================================
    public class StudioForm : Form
    {
        static readonly Guid MESH_SERVICE_UUID = new Guid("0000fef1-0000-1000-8000-00805f9b34fb");
        static readonly Guid CHAR_LOW_UUID     = new Guid("c4edc000-9daf-11e3-8003-00025b000b00");
        static readonly Guid CHAR_HIGH_UUID    = new Guid("c4edc000-9daf-11e3-8004-00025b000b00");

        private BluetoothLEDevice bleDevice;
        private GattDeviceService meshService;
        private GattCharacteristic charLow;
        private GattCharacteristic charHigh;
        private ulong garlandAddress = 0x00025B001583UL;
        private uint seqCounter = (uint)(DateTime.UtcNow.Ticks & 0xFFFFFF);
        private readonly object bleLock = new object();
        private readonly BlockingCollection<byte[]> rxMaspQueue = new BlockingCollection<byte[]>();

        // UI Controls
        private Label lblStatus;
        private Label lblLastCmd;
        private Button btnConnect;
        private Button btnPowerOn;
        private Button btnPowerOff;
        private TrackBar tbBrightness;
        private Label lblBrightnessVal;
        private TrackBar tbRainbowSpeed;
        private Label lblRainbowSpeed;
        private TextBox txtLog;
        private NumericUpDown numCmd;
        private NumericUpDown numParam;

        // Software Animation Engine
        private System.Windows.Forms.Timer animTimer;
        private string activeEffect = "NONE";
        private int animStep = 0;
        private readonly int[] rainbowPresets = new int[] { 4, 6, 2, 3, 1, 5 }; // Red, Gold, Green, Cyan, Blue, Magenta
        private readonly string[] rainbowNames = new string[] { "Rouge", "Or/Jaune", "Vert", "Cyan", "Bleu", "Magenta" };
        private readonly Random rng = new Random();

        public StudioForm()
        {
            Text = "Feeric Lights Studio — Contrôleur Bluetooth LE & Mode Arc-en-Ciel (PC Windows)";
            Size = new Size(980, 760);
            StartPosition = FormStartPosition.CenterScreen;
            BackColor = Color.FromArgb(18, 22, 31);
            ForeColor = Color.FromArgb(235, 240, 250);
            Font = new Font("Segoe UI", 9.5f, FontStyle.Regular);

            BuildUi();

            animTimer = new System.Windows.Forms.Timer();
            animTimer.Interval = 600;
            animTimer.Tick += OnAnimationTick;

            Shown += (s, e) => Task.Run(() => ConnectToGarlandAsync());
            FormClosing += (s, e) => CleanupBle();
        }

        private void BuildUi()
        {
            // 1. Top Header Panel
            Panel pnlHeader = new Panel
            {
                Dock = DockStyle.Top,
                Height = 76,
                BackColor = Color.FromArgb(26, 32, 46),
                Padding = new Padding(16, 12, 16, 12)
            };

            Label lblTitle = new Label
            {
                Text = "🎄 Feeric Lights Studio (CSRMesh BLE)",
                Font = new Font("Segoe UI Semibold", 14f, FontStyle.Bold),
                ForeColor = Color.FromArgb(255, 215, 64),
                Location = new Point(16, 10),
                AutoSize = true
            };

            lblStatus = new Label
            {
                Text = "Statut : Initialisation Bluetooth LE...",
                Font = new Font("Segoe UI", 9.5f, FontStyle.Regular),
                ForeColor = Color.FromArgb(130, 220, 255),
                Location = new Point(18, 42),
                AutoSize = true
            };

            btnConnect = CreateStyledButton("🔄 Reconnecter BLE", Color.FromArgb(45, 90, 160), new Point(540, 18), new Size(150, 40));
            btnConnect.Click += (s, e) => Task.Run(() => ConnectToGarlandAsync());

            btnPowerOn = CreateStyledButton("⚡ ALLUMER", Color.FromArgb(0, 150, 80), new Point(700, 18), new Size(120, 40));
            btnPowerOn.Click += (s, e) =>
            {
                StopSoftwareEffect();
                Task.Run(() =>
                {
                    SendFeericCommand(4, 3, "Rampe MULTI");
                    SendFeericCommand(2, tbBrightness.Value, "Luminosité");
                    SendFeericCommand(1, 6, "Power ON (Fixe)");
                });
            };

            btnPowerOff = CreateStyledButton("🌙 ÉTEINDRE", Color.FromArgb(170, 40, 55), new Point(830, 18), new Size(120, 40));
            btnPowerOff.Click += (s, e) =>
            {
                StopSoftwareEffect();
                Task.Run(() => SendFeericCommand(1, 7, "Power OFF"));
            };

            pnlHeader.Controls.AddRange(new Control[] { lblTitle, lblStatus, btnConnect, btnPowerOn, btnPowerOff });
            Controls.Add(pnlHeader);

            // 2. Color Presets Group (Command = 5, Param = 1..7)
            GroupBox grpColors = CreateGroupBox("🎨 1. Couleurs Matérielles (doCommand 5, param 1..7 — 3-bit RGB)", new Point(16, 90), new Size(935, 95));
            var colorDefs = new[]
            {
                new { Param = 4, Name = "Rouge (4)",   Bg = Color.FromArgb(220, 35, 60),  Fg = Color.White },
                new { Param = 6, Name = "Or / Jaune (6)", Bg = Color.FromArgb(230, 180, 0), Fg = Color.Black },
                new { Param = 2, Name = "Vert (2)",    Bg = Color.FromArgb(0, 175, 80),   Fg = Color.White },
                new { Param = 3, Name = "Cyan (3)",    Bg = Color.FromArgb(0, 185, 215),  Fg = Color.Black },
                new { Param = 1, Name = "Bleu (1)",    Bg = Color.FromArgb(40, 95, 245),  Fg = Color.White },
                new { Param = 5, Name = "Magenta (5)", Bg = Color.FromArgb(205, 25, 115), Fg = Color.White },
                new { Param = 7, Name = "Blanc (7)",   Bg = Color.FromArgb(235, 240, 250), Fg = Color.Black }
            };

            int cx = 14;
            foreach (var c in colorDefs)
            {
                int p = c.Param;
                string n = c.Name;
                Button b = CreateStyledButton(n, c.Bg, new Point(cx, 30), new Size(124, 48));
                b.ForeColor = c.Fg;
                b.Click += (s, e) =>
                {
                    StopSoftwareEffect();
                    Task.Run(() => SendFeericCommand(5, p, "Couleur " + n));
                };
                grpColors.Controls.Add(b);
                cx += 130;
            }
            Controls.Add(grpColors);

            // 3. Rainbow & Custom PC Dynamic Effects Group
            GroupBox grpRainbow = CreateGroupBox("🌈 2. Mode Arc-en-Ciel & Effets Dynamiques Pilotés par le PC", new Point(16, 195), new Size(935, 115));

            Button btnRainbow = CreateStyledButton("🌈 Arc-en-Ciel Fluide", Color.FromArgb(135, 50, 190), new Point(14, 28), new Size(185, 44));
            btnRainbow.Click += (s, e) => StartSoftwareEffect("RAINBOW", "🌈 Mode Arc-en-Ciel actif");

            Button btnAurora = CreateStyledButton("🌌 Aurore Boréale", Color.FromArgb(0, 135, 155), new Point(208, 28), new Size(175, 44));
            btnAurora.Click += (s, e) => StartSoftwareEffect("AURORA", "🌌 Mode Aurore Boréale actif");

            Button btnFireplace = CreateStyledButton("🔥 Feu de Cheminée", Color.FromArgb(205, 85, 20), new Point(392, 28), new Size(175, 44));
            btnFireplace.Click += (s, e) => StartSoftwareEffect("FIRE", "🔥 Mode Feu de Cheminée actif");

            Button btnParty = CreateStyledButton("🎉 Stroboscope Party", Color.FromArgb(195, 30, 105), new Point(576, 28), new Size(175, 44));
            btnParty.Click += (s, e) => StartSoftwareEffect("PARTY", "🎉 Mode Party actif");

            Button btnStopAnim = CreateStyledButton("⏹ Stop Effet PC", Color.FromArgb(70, 80, 95), new Point(760, 28), new Size(155, 44));
            btnStopAnim.Click += (s, e) => StopSoftwareEffect();

            lblRainbowSpeed = new Label
            {
                Text = "Vitesse de transition Arc-en-Ciel : 600 ms / couleur",
                Location = new Point(16, 82),
                AutoSize = true,
                ForeColor = Color.FromArgb(190, 205, 230)
            };

            tbRainbowSpeed = new TrackBar
            {
                Minimum = 250,
                Maximum = 2500,
                Value = 600,
                TickFrequency = 250,
                Location = new Point(340, 76),
                Size = new Size(575, 35)
            };
            tbRainbowSpeed.Scroll += (s, e) =>
            {
                animTimer.Interval = tbRainbowSpeed.Value;
                lblRainbowSpeed.Text = "Vitesse de transition Arc-en-Ciel : " + tbRainbowSpeed.Value + " ms / couleur";
            };

            grpRainbow.Controls.AddRange(new Control[] { btnRainbow, btnAurora, btnFireplace, btnParty, btnStopAnim, lblRainbowSpeed, tbRainbowSpeed });
            Controls.Add(grpRainbow);

            // 4. Hardware Animation Modes (Command = 1, Param = 1..6) & Ramp Mode (Command = 4, Param = 1..3)
            GroupBox grpModes = CreateGroupBox("✨ 3. Modes d'Animation Matériels (doCommand 1, param 1..6) & Rampes LED (doCommand 4)", new Point(16, 320), new Size(935, 95));
            var modeDefs = new[]
            {
                new { Cmd = 1, Param = 6, Title = "Fixe Continu (1,6)", Color = Color.FromArgb(45, 110, 90) },
                new { Cmd = 1, Param = 1, Title = "Combinaison (1,1)",  Color = Color.FromArgb(60, 75, 120) },
                new { Cmd = 1, Param = 2, Title = "Séquentiel (1,2)",   Color = Color.FromArgb(60, 75, 120) },
                new { Cmd = 1, Param = 4, Title = "Scintillement (1,4)",Color = Color.FromArgb(60, 75, 120) },
                new { Cmd = 4, Param = 1, Title = "Rampe A (4,1)",      Color = Color.FromArgb(95, 70, 110) },
                new { Cmd = 4, Param = 2, Title = "Rampe B (4,2)",      Color = Color.FromArgb(95, 70, 110) },
                new { Cmd = 4, Param = 3, Title = "Multi-Rampes (4,3)", Color = Color.FromArgb(115, 80, 40) }
            };

            int mx = 14;
            foreach (var m in modeDefs)
            {
                int c = m.Cmd;
                int p = m.Param;
                string t = m.Title;
                Button b = CreateStyledButton(t, m.Color, new Point(mx, 30), new Size(124, 48));
                b.Click += (s, e) =>
                {
                    StopSoftwareEffect();
                    Task.Run(() => SendFeericCommand(c, p, t));
                };
                grpModes.Controls.Add(b);
                mx += 130;
            }
            Controls.Add(grpModes);

            // 5. Brightness & Timer Group (Command = 2 & Command = 3)
            GroupBox grpBright = CreateGroupBox("☀️ 4. Luminosité (doCommand 2, 1..127) & Minuterie Matérielle (doCommand 3, 0..2)", new Point(16, 425), new Size(520, 115));

            lblBrightnessVal = new Label
            {
                Text = "Intensité : 100% (0x7F = 127)",
                Location = new Point(14, 28),
                AutoSize = true
            };

            tbBrightness = new TrackBar
            {
                Minimum = 1,
                Maximum = 127,
                Value = 127,
                TickFrequency = 16,
                Location = new Point(14, 52),
                Size = new Size(250, 45)
            };
            tbBrightness.MouseUp += (s, e) =>
            {
                int val = tbBrightness.Value;
                int pct = (int)Math.Round(val * 100.0 / 127.0);
                lblBrightnessVal.Text = string.Format("Intensité : {0}% (0x{1:X2} = {1})", pct, val);
                Task.Run(() => SendFeericCommand(2, val, "Luminosité " + pct + "%"));
            };
            tbBrightness.Scroll += (s, e) =>
            {
                int val = tbBrightness.Value;
                int pct = (int)Math.Round(val * 100.0 / 127.0);
                lblBrightnessVal.Text = string.Format("Intensité : {0}% (0x{1:X2} = {1})", pct, val);
            };

            Button btnB25 = CreateStyledButton("25%", Color.FromArgb(55, 65, 85), new Point(275, 26), new Size(52, 34));
            btnB25.Click += (s, e) => SetBrightnessPreset(0x0A);

            Button btnB50 = CreateStyledButton("50%", Color.FromArgb(55, 65, 85), new Point(333, 26), new Size(52, 34));
            btnB50.Click += (s, e) => SetBrightnessPreset(0x32);

            Button btnB75 = CreateStyledButton("75%", Color.FromArgb(55, 65, 85), new Point(391, 26), new Size(52, 34));
            btnB75.Click += (s, e) => SetBrightnessPreset(0x5A);

            Button btnB100 = CreateStyledButton("100%", Color.FromArgb(55, 65, 85), new Point(449, 26), new Size(55, 34));
            btnB100.Click += (s, e) => SetBrightnessPreset(0x7F);

            Button btnT0 = CreateStyledButton("Timer OFF", Color.FromArgb(65, 70, 80), new Point(275, 68), new Size(72, 34));
            btnT0.Click += (s, e) => Task.Run(() => SendFeericCommand(3, 0, "Timer OFF"));

            Button btnT1 = CreateStyledButton("Timer 6h/18h", Color.FromArgb(65, 70, 80), new Point(352, 68), new Size(78, 34));
            btnT1.Click += (s, e) => Task.Run(() => SendFeericCommand(3, 1, "Timer 6h/18h"));

            Button btnT2 = CreateStyledButton("6h/6h/3h/9h", Color.FromArgb(65, 70, 80), new Point(435, 68), new Size(72, 34));
            btnT2.Click += (s, e) => Task.Run(() => SendFeericCommand(3, 2, "Timer 6h/6h/3h/9h"));

            grpBright.Controls.AddRange(new Control[] { lblBrightnessVal, tbBrightness, btnB25, btnB50, btnB75, btnB100, btnT0, btnT1, btnT2 });
            Controls.Add(grpBright);

            // 6. Custom Command Lab Group
            GroupBox grpLab = CreateGroupBox("🧪 5. Labo Debug Direct : doCommand(cmd, param)", new Point(550, 425), new Size(401, 115));

            Label lblC = new Label { Text = "Cmd :", Location = new Point(14, 34), AutoSize = true };
            numCmd = new NumericUpDown
            {
                Minimum = 0,
                Maximum = 255,
                Value = 5,
                Location = new Point(62, 31),
                Size = new Size(60, 28),
                BackColor = Color.FromArgb(30, 36, 50),
                ForeColor = Color.White
            };

            Label lblP = new Label { Text = "Param :", Location = new Point(132, 34), AutoSize = true };
            numParam = new NumericUpDown
            {
                Minimum = 0,
                Maximum = 255,
                Value = 4,
                Location = new Point(190, 31),
                Size = new Size(60, 28),
                BackColor = Color.FromArgb(30, 36, 50),
                ForeColor = Color.White
            };

            Button btnSendCustom = CreateStyledButton("Envoyer Test", Color.FromArgb(0, 130, 180), new Point(262, 27), new Size(124, 36));
            btnSendCustom.Click += (s, e) =>
            {
                int c = (int)numCmd.Value;
                int p = (int)numParam.Value;
                Task.Run(() => SendFeericCommand(c, p, string.Format("Test Custom ({0},{1})", c, p)));
            };

            lblLastCmd = new Label
            {
                Text = "Dernière commande : Aucune",
                Location = new Point(14, 74),
                AutoSize = true,
                ForeColor = Color.FromArgb(255, 215, 64)
            };

            grpLab.Controls.AddRange(new Control[] { lblC, numCmd, lblP, numParam, btnSendCustom, lblLastCmd });
            Controls.Add(grpLab);

            // 7. Live BLE Packet Log Console
            GroupBox grpLog = CreateGroupBox("📡 Journal Temps Réel CSRMesh BLE (TX / RX / MASP)", new Point(16, 548), new Size(935, 160));
            txtLog = new TextBox
            {
                Multiline = true,
                ReadOnly = true,
                ScrollBars = ScrollBars.Vertical,
                Dock = DockStyle.Fill,
                BackColor = Color.FromArgb(12, 15, 22),
                ForeColor = Color.FromArgb(125, 240, 165),
                Font = new Font("Consolas", 9f, FontStyle.Regular),
                BorderStyle = BorderStyle.None
            };
            grpLog.Controls.Add(txtLog);
            Controls.Add(grpLog);
        }

        private void SetBrightnessPreset(int val)
        {
            tbBrightness.Value = val;
            int pct = (int)Math.Round(val * 100.0 / 127.0);
            lblBrightnessVal.Text = string.Format("Intensité : {0}% (0x{1:X2} = {1})", pct, val);
            Task.Run(() => SendFeericCommand(2, val, "Luminosité " + pct + "%"));
        }

        private void StartSoftwareEffect(string mode, string label)
        {
            activeEffect = mode;
            animStep = 0;
            animTimer.Interval = (mode == "PARTY") ? 280 : tbRainbowSpeed.Value;
            animTimer.Start();
            UpdateStatusLabel(label);
            AppendLog(">>> Démarrage de l'animation PC : " + label);
        }

        private void StopSoftwareEffect()
        {
            if (animTimer != null && animTimer.Enabled)
            {
                animTimer.Stop();
                activeEffect = "NONE";
                AppendLog(">>> Animation PC arrêtée.");
            }
        }

        private void OnAnimationTick(object sender, EventArgs e)
        {
            if (activeEffect == "NONE") return;

            Task.Run(() =>
            {
                if (activeEffect == "RAINBOW" || activeEffect == "PARTY")
                {
                    int idx = animStep % rainbowPresets.Length;
                    int colorParam = rainbowPresets[idx];
                    string colorName = rainbowNames[idx];
                    animStep++;
                    SendFeericCommand(5, colorParam, "🌈 Arc-en-Ciel : " + colorName, broadcastOnly: true);
                }
                else if (activeEffect == "AURORA")
                {
                    int[] auroraColors = new int[] { 2, 3, 1, 5, 3 }; // Green, Cyan, Blue, Magenta, Cyan
                    int colorParam = auroraColors[animStep % auroraColors.Length];
                    int bright = (animStep % 2 == 0) ? 0x7F : 0x45;
                    animStep++;
                    SendFeericCommand(2, bright, "Aurore Luminosité", broadcastOnly: true);
                    SendFeericCommand(5, colorParam, "🌌 Aurore Couleur " + colorParam, broadcastOnly: true);
                }
                else if (activeEffect == "FIRE")
                {
                    int colorParam = (animStep % 3 == 0) ? 6 : 4; // Gold or Red
                    int bright = rng.Next(45, 127);
                    animStep++;
                    SendFeericCommand(2, bright, "🔥 Flamme Intensité", broadcastOnly: true);
                    SendFeericCommand(5, colorParam, "🔥 Flamme Couleur", broadcastOnly: true);
                }
            });
        }

        private GroupBox CreateGroupBox(string title, Point loc, Size sz)
        {
            return new GroupBox
            {
                Text = title,
                Location = loc,
                Size = sz,
                ForeColor = Color.FromArgb(200, 215, 240),
                Font = new Font("Segoe UI Semibold", 9.5f, FontStyle.Bold),
                Padding = new Padding(10)
            };
        }

        private Button CreateStyledButton(string text, Color bg, Point loc, Size sz)
        {
            Button b = new Button
            {
                Text = text,
                Location = loc,
                Size = sz,
                BackColor = bg,
                ForeColor = Color.White,
                FlatStyle = FlatStyle.Flat,
                Font = new Font("Segoe UI Semibold", 9f, FontStyle.Bold),
                Cursor = Cursors.Hand
            };
            b.FlatAppearance.BorderSize = 0;
            return b;
        }

        private void AppendLog(string msg)
        {
            if (IsDisposed) return;
            if (InvokeRequired)
            {
                BeginInvoke(new Action<string>(AppendLog), msg);
                return;
            }
            string line = "[" + DateTime.Now.ToString("HH:mm:ss.fff") + "] " + msg + Environment.NewLine;
            txtLog.AppendText(line);
        }

        private void UpdateStatusLabel(string msg)
        {
            if (IsDisposed) return;
            if (InvokeRequired)
            {
                BeginInvoke(new Action<string>(UpdateStatusLabel), msg);
                return;
            }
            lblStatus.Text = "Statut : " + msg;
        }

        private void UpdateLastCmdLabel(string msg)
        {
            if (IsDisposed) return;
            if (InvokeRequired)
            {
                BeginInvoke(new Action<string>(UpdateLastCmdLabel), msg);
                return;
            }
            lblLastCmd.Text = msg;
        }

        // =====================================================================
        // WinRT Async Helpers & BLE Stack
        // =====================================================================
        static T WaitOp<T>(IAsyncOperation<T> op, int timeoutMs = 8000)
        {
            using (var evt = new ManualResetEventSlim(false))
            {
                op.Completed = (info, status) => evt.Set();
                if (op.Status != AsyncStatus.Started) evt.Set();
                evt.Wait(timeoutMs);
                return op.GetResults();
            }
        }

        private void CleanupBle()
        {
            try { if (meshService != null) meshService.Dispose(); } catch { }
            try { if (bleDevice != null) bleDevice.Dispose(); } catch { }
            meshService = null;
            bleDevice = null;
            charLow = null;
            charHigh = null;
        }

        private void ConnectToGarlandAsync()
        {
            lock (bleLock)
            {
                try
                {
                    CleanupBle();
                    UpdateStatusLabel("Scan Bluetooth LE de la guirlande CSRmesh (00:02:5B:00:15:83)...");
                    AppendLog("Scan BLE actif démarré...");

                    bool seenAdv = false;
                    int lastRssi = -70;
                    var watcher = new BluetoothLEAdvertisementWatcher { ScanningMode = BluetoothLEScanningMode.Active };
                    watcher.Received += (w, args) =>
                    {
                        if (args.BluetoothAddress == garlandAddress ||
                            args.Advertisement.ServiceUuids.Contains(MESH_SERVICE_UUID) ||
                            (args.Advertisement.LocalName ?? "").Contains("CSR"))
                        {
                            garlandAddress = args.BluetoothAddress;
                            lastRssi = args.RawSignalStrengthInDBm;
                            seenAdv = true;
                        }
                    };
                    watcher.Start();
                    for (int i = 0; i < 35 && !seenAdv; i++) Thread.Sleep(100);
                    watcher.Stop();

                    AppendLog(string.Format("Connexion GATT vers 0x{0:X12} (seenAdv={1}, RSSI={2} dBm)...", garlandAddress, seenAdv, lastRssi));
                    bleDevice = WaitOp(BluetoothLEDevice.FromBluetoothAddressAsync(garlandAddress));
                    if (bleDevice == null)
                    {
                        UpdateStatusLabel("Échec d'ouverture du périphérique BLE.");
                        return;
                    }

                    var svcRes = WaitOp(bleDevice.GetGattServicesForUuidAsync(MESH_SERVICE_UUID, BluetoothCacheMode.Uncached));
                    if (svcRes.Status != GattCommunicationStatus.Success || svcRes.Services.Count == 0)
                    {
                        UpdateStatusLabel("Guirlande hors de portée ou en veille (" + svcRes.Status + ") — Débranchez/rebranchez-la.");
                        AppendLog("Erreur service 0xFEF1 : " + svcRes.Status);
                        return;
                    }

                    meshService = svcRes.Services[0];
                    var charsRes = WaitOp(meshService.GetCharacteristicsAsync(BluetoothCacheMode.Uncached));
                    charLow = charsRes.Characteristics.FirstOrDefault(c => c.Uuid == CHAR_LOW_UUID);
                    charHigh = charsRes.Characteristics.FirstOrDefault(c => c.Uuid == CHAR_HIGH_UUID);

                    if (charLow == null || charHigh == null)
                    {
                        UpdateStatusLabel("Caractéristiques CSRMesh 8003/8004 introuvable.");
                        return;
                    }

                    byte[] pendingLow = null;
                    object rxLock = new object();
                    charLow.ValueChanged += (c, a) =>
                    {
                        byte[] b = new byte[a.CharacteristicValue.Length];
                        DataReader.FromBuffer(a.CharacteristicValue).ReadBytes(b);
                        lock (rxLock) { pendingLow = b; }
                    };
                    charHigh.ValueChanged += (c, a) =>
                    {
                        byte[] b = new byte[a.CharacteristicValue.Length];
                        DataReader.FromBuffer(a.CharacteristicValue).ReadBytes(b);
                        lock (rxLock)
                        {
                            byte[] full = b;
                            if (pendingLow != null)
                            {
                                full = new byte[pendingLow.Length + b.Length];
                                Array.Copy(pendingLow, 0, full, 0, pendingLow.Length);
                                Array.Copy(b, 0, full, pendingLow.Length, b.Length);
                                pendingLow = null;
                            }
                            OnIncomingMeshPacket(full);
                        }
                    };

                    WaitOp(charHigh.WriteClientCharacteristicConfigurationDescriptorAsync(GattClientCharacteristicConfigurationDescriptorValue.Notify));
                    WaitOp(charLow.WriteClientCharacteristicConfigurationDescriptorAsync(GattClientCharacteristicConfigurationDescriptorValue.Notify));

                    AppendLog("Abonné aux notifications 8003/8004. Vérification MASP (2s)...");
                    byte[] rxBeacon;
                    while (rxMaspQueue.TryTake(out rxBeacon)) { }

                    if (rxMaspQueue.TryTake(out rxBeacon, 2000))
                    {
                        AppendLog("Guirlande non associée détectée ! Lancement de l'association MASP ECDH P-192...");
                        UpdateStatusLabel("Association MASP ECDH P-192 en cours...");
                        byte[] uuidHash4 = new byte[] { 0x6B, 0xD7, 0x8B, 0x7A };
                        if (rxBeacon[0] == 0x01 && rxBeacon.Length >= 17)
                        {
                            byte[] uuid16 = new byte[16];
                            Array.Copy(rxBeacon, 1, uuid16, 0, 16);
                            using (var sha = SHA256.Create())
                            {
                                byte[] dig = sha.ComputeHash(uuid16);
                                dig[28] &= 0x7F;
                                uuidHash4 = new byte[] { dig[28], dig[29], dig[30], dig[31] };
                            }
                        }
                        else if (rxBeacon.Length >= 5)
                        {
                            Array.Copy(rxBeacon, 1, uuidHash4, 0, 4);
                        }
                        RunMaspAssociation(uuidHash4, 0x8001, "8888");
                    }

                    // Wake up all LED ramps at 100% brightness
                    SendRawBleMeshPacket(BuildLightSetLevelPacket("8888", seqCounter += 2, 0x8001, 0xFF));
                    SendRawBleMeshPacket(BuildMcpCommandPacket("8888", seqCounter += 2, 0x0000, 4, 3));
                    SendRawBleMeshPacket(BuildMcpCommandPacket("8888", seqCounter += 2, 0x0000, 2, 0x7F));
                    SendRawBleMeshPacket(BuildMcpCommandPacket("8888", seqCounter += 2, 0x0000, 1, 6));

                    UpdateStatusLabel(string.Format("Connecté à CSRmesh (00:02:5B:00:15:83) — RSSI {0} dBm — Prêt !", lastRssi));
                    AppendLog("Guirlande connectée et initialisée (Multi-Rampes + Luminosité 100% + Fixe).");
                }
                catch (Exception ex)
                {
                    UpdateStatusLabel("Erreur BLE : " + ex.Message);
                    AppendLog("Exception BLE : " + ex.Message);
                }
            }
        }

        private void SendFeericCommand(int command, int param, string description, bool broadcastOnly = false)
        {
            lock (bleLock)
            {
                if (charLow == null || charHigh == null)
                {
                    AppendLog("Non connecté — tentative de reconnexion...");
                    ConnectToGarlandAsync();
                    if (charLow == null || charHigh == null) return;
                }

                try
                {
                    byte[] pkt0 = BuildMcpCommandPacket("8888", seqCounter += 2, 0x0000, command, param);
                    SendRawBleMeshPacket(pkt0);

                    if (!broadcastOnly)
                    {
                        Thread.Sleep(60);
                        byte[] pkt1 = BuildMcpCommandPacket("8888", seqCounter += 2, 0x8001, command, param);
                        SendRawBleMeshPacket(pkt1);
                    }

                    UpdateLastCmdLabel(string.Format("{0} → doCommand({1}, {2})", description, command, param));
                    AppendLog(string.Format("TX doCommand({0}, {1}) [{2}] -> {3}", command, param, description, BitConverter.ToString(pkt0).Replace("-", " ")));
                }
                catch (Exception ex)
                {
                    AppendLog("Erreur d'envoi BLE : " + ex.Message);
                }
            }
        }

        private static IBuffer ToBuffer(byte[] data)
        {
            var dw = new DataWriter();
            dw.WriteBytes(data);
            return dw.DetachBuffer();
        }

        private void SendRawBleMeshPacket(byte[] packet)
        {
            if (packet.Length <= 20)
            {
                WaitOp(charHigh.WriteValueAsync(ToBuffer(packet), GattWriteOption.WriteWithResponse));
            }
            else
            {
                byte[] low20 = new byte[20];
                byte[] highRem = new byte[packet.Length - 20];
                Array.Copy(packet, 0, low20, 0, 20);
                Array.Copy(packet, 20, highRem, 0, packet.Length - 20);
                WaitOp(charLow.WriteValueAsync(ToBuffer(low20), GattWriteOption.WriteWithResponse));
                WaitOp(charHigh.WriteValueAsync(ToBuffer(highRem), GattWriteOption.WriteWithResponse));
            }
        }

        // =====================================================================
        // CSRMesh Crypto (Verified Exact MCP + MASP Implementation)
        // =====================================================================
        static readonly byte[] MASP_XOR_MASK = new byte[] {
            0x52, 0x20, 0x2A, 0x39, 0x40, 0x18, 0xC4, 0x41,
            0xAA, 0xD1, 0x7D, 0x26, 0x05, 0xD5, 0x7F, 0xAE,
            0x2C, 0xB3, 0x0E, 0xF5, 0xC5, 0x2D, 0x06, 0x24, 0x15
        };

        static byte[] DeriveKey(string phrase, string suffix)
        {
            byte[] input = Encoding.UTF8.GetBytes(phrase + "\0" + suffix);
            using (var sha = SHA256.Create())
            {
                byte[] digest = sha.ComputeHash(input);
                byte[] key = new byte[16];
                for (int i = 0; i < 16; i++) key[i] = digest[31 - i];
                return key;
            }
        }

        static byte[] HmacSha256TruncReversed(byte[] data, byte[] key, int len)
        {
            using (var hmac = new HMACSHA256(key))
            {
                byte[] full = hmac.ComputeHash(data);
                byte[] res = new byte[len];
                for (int i = 0; i < len; i++) res[i] = full[31 - i];
                return res;
            }
        }

        static byte[] ComputePacketHmac8(byte[] key16, byte[] body)
        {
            byte[] preImage = new byte[8 + body.Length];
            Array.Copy(body, 0, preImage, 8, body.Length);
            return HmacSha256TruncReversed(preImage, key16, 8);
        }

        static byte[] AesEcbEncrypt(byte[] key16, byte[] block16)
        {
            using (var aes = Aes.Create())
            {
                aes.KeySize = 128;
                aes.Key = key16;
                aes.Mode = CipherMode.ECB;
                aes.Padding = PaddingMode.None;
                using (var enc = aes.CreateEncryptor())
                {
                    return enc.TransformFinalBlock(block16, 0, 16);
                }
            }
        }

        static byte[] WrapMaspPacket(byte[] plainMasp, byte ttl = 0xFF)
        {
            byte[] enc = new byte[plainMasp.Length];
            for (int i = 0; i < plainMasp.Length; i++)
                enc[i] = (byte)(plainMasp[i] ^ MASP_XOR_MASK[Math.Min(i, MASP_XOR_MASK.Length - 1)]);
            byte[] maspKey = DeriveKey("", "MASP");
            byte[] mac8 = ComputePacketHmac8(maspKey, enc);
            byte[] full = new byte[enc.Length + 9];
            Array.Copy(enc, 0, full, 0, enc.Length);
            Array.Copy(mac8, 0, full, enc.Length, 8);
            full[full.Length - 1] = ttl;
            return full;
        }

        static byte[] EncryptMcpPdu(string passphrase, byte[] pdu, byte ttl = 0x37)
        {
            byte[] netKey = DeriveKey(passphrase, "MCP");
            byte[] iv = new byte[16];
            iv[0] = pdu[0]; iv[1] = pdu[1]; iv[2] = pdu[2];
            iv[3] = 0x00;
            iv[4] = pdu[3]; iv[5] = pdu[4];

            byte[] ks = AesEcbEncrypt(netKey, iv);
            byte[] encBody = (byte[])pdu.Clone();
            for (int i = 0; i < pdu.Length - 5; i++)
                encBody[5 + i] = (byte)(pdu[5 + i] ^ ks[i]);

            byte[] mac8 = ComputePacketHmac8(netKey, encBody);
            byte[] full = new byte[pdu.Length + 9];
            Array.Copy(encBody, 0, full, 0, encBody.Length);
            Array.Copy(mac8, 0, full, encBody.Length, 8);
            full[full.Length - 1] = ttl;
            return full;
        }

        static byte[] BuildMcpCommandPacket(string passphrase, uint seq, ushort dstId, int cmd, int param)
        {
            byte[] pdu = new byte[] {
                (byte)(seq & 0xFF), (byte)((seq >> 8) & 0xFF), (byte)((seq >> 16) & 0xFF),
                0x00, 0x80,
                (byte)(dstId & 0xFF), (byte)((dstId >> 8) & 0xFF),
                0x8A,
                0x02,
                0xFF,
                (byte)(cmd & 0xFF),
                (byte)(param & 0xFF),
                0x01,
                0x00,
                0x00
            };
            return EncryptMcpPdu(passphrase, pdu, 0x37);
        }

        static byte[] BuildLightSetLevelPacket(string passphrase, uint seq, ushort dstId, byte level)
        {
            byte[] pdu = new byte[] {
                (byte)(seq & 0xFF), (byte)((seq >> 8) & 0xFF), (byte)((seq >> 16) & 0xFF),
                0x00, 0x80,
                (byte)(dstId & 0xFF), (byte)((destIdMsb(dstId))),
                0x8A, 0x00, level
            };
            pdu[6] = (byte)((dstId >> 8) & 0xFF);
            return EncryptMcpPdu(passphrase, pdu, 0x37);
        }

        static byte destIdMsb(ushort dstId) { return (byte)((dstId >> 8) & 0xFF); }

        private void OnIncomingMeshPacket(byte[] pkt)
        {
            if (pkt == null || pkt.Length < 10) return;
            int bodyLen = pkt.Length - 9;
            byte[] body = new byte[bodyLen];
            Array.Copy(pkt, 0, body, 0, bodyLen);
            byte[] rxMac = new byte[8];
            Array.Copy(pkt, bodyLen, rxMac, 0, 8);

            byte[] maspKey = DeriveKey("", "MASP");
            byte[] maspMac = ComputePacketHmac8(maspKey, body);
            if (maspMac.SequenceEqual(rxMac))
            {
                byte[] plainMasp = new byte[bodyLen];
                for (int i = 0; i < bodyLen; i++)
                    plainMasp[i] = (byte)(body[i] ^ MASP_XOR_MASK[Math.Min(i, MASP_XOR_MASK.Length - 1)]);
                rxMaspQueue.Add(plainMasp);
                return;
            }
            AppendLog("RX BLE (" + pkt.Length + "B) : " + BitConverter.ToString(pkt).Replace("-", " "));
        }

        private byte[] SendMaspAndWait(byte[] plainReq, byte expectedOpcode, int expectedIdx = -1)
        {
            byte seqByte = (byte)rng.Next(0, 256);
            for (int attempt = 0; attempt < 5; attempt++)
            {
                plainReq[plainReq.Length - 1] = seqByte++;
                byte[] pkt = WrapMaspPacket(plainReq, 0xFF);
                SendRawBleMeshPacket(pkt);
                var sw = System.Diagnostics.Stopwatch.StartNew();
                while (sw.ElapsedMilliseconds < 1500)
                {
                    byte[] rx;
                    if (rxMaspQueue.TryTake(out rx, 200))
                    {
                        if (rx.Length > 0 && rx[0] == expectedOpcode)
                        {
                            if (expectedIdx < 0 || (rx.Length > 5 && rx[5] == (byte)expectedIdx))
                                return rx;
                        }
                    }
                }
            }
            return null;
        }

        private void RunMaspAssociation(byte[] uuidHash4, ushort assignedDeviceId, string passphrase)
        {
            byte[] privBytes = new byte[25];
            using (var r = RandomNumberGenerator.Create()) r.GetBytes(privBytes, 0, 24);
            privBytes[23] &= 0x7F;
            BigInteger privK = new BigInteger(privBytes);
            var pubPt = P192.ScalarMult(privK, Tuple.Create(P192.Gx, P192.Gy));
            byte[] localPubKey48 = new byte[48];
            Array.Copy(P192.To24BytesLE(pubPt.Item2), 0, localPubKey48, 0, 24);
            Array.Copy(P192.To24BytesLE(pubPt.Item1), 0, localPubKey48, 24, 24);

            byte[] assocReq = new byte[15];
            assocReq[0] = 0x02;
            Array.Copy(uuidHash4, 0, assocReq, 1, 4);
            assocReq[6] = 0x01;
            byte[] assocResp = SendMaspAndWait(assocReq, 0x03);
            if (assocResp == null) return;
            byte role = assocResp[5];

            byte[] remY = new byte[24], remX = new byte[24];
            for (int h = 0; h < 6; h++)
            {
                byte[] pkReq = new byte[15];
                pkReq[0] = 0x04;
                Array.Copy(uuidHash4, 0, pkReq, 1, 4);
                pkReq[5] = (byte)h;
                Array.Copy(localPubKey48, h * 8, pkReq, 6, 8);
                byte[] pkResp = SendMaspAndWait(pkReq, 0x05, h);
                if (pkResp == null) return;
                if (h < 3) Array.Copy(pkResp, 6, remY, h * 8, 8);
                else Array.Copy(pkResp, 6, remX, (h - 3) * 8, 8);
            }

            var sharedPt = P192.ScalarMult(privK, Tuple.Create(P192.From24BytesLE(remX), P192.From24BytesLE(remY)));
            byte[] sharedSecret24 = P192.To24BytesLE(sharedPt.Item1);

            byte[] localRand8 = new byte[8];
            using (var r = RandomNumberGenerator.Create()) r.GetBytes(localRand8);
            byte[] confirmData = (role == 0) ? new byte[56] : new byte[66];
            if (role == 0) Array.Copy(localPubKey48, 0, confirmData, 0, 48);
            else { confirmData[0] = 1; confirmData[8] = 1; Array.Copy(localPubKey48, 0, confirmData, 10, 48); }
            byte[] localConfirm8 = HmacSha256TruncReversed(confirmData, localRand8, 8);

            byte[] confReq = new byte[14];
            confReq[0] = 0x06;
            Array.Copy(uuidHash4, 0, confReq, 1, 4);
            Array.Copy(localConfirm8, 0, confReq, 5, 8);
            if (SendMaspAndWait(confReq, 0x07) == null) return;

            byte[] randReq = new byte[14];
            randReq[0] = 0x08;
            Array.Copy(uuidHash4, 0, randReq, 1, 4);
            Array.Copy(localRand8, 0, randReq, 5, 8);
            if (SendMaspAndWait(randReq, 0x09) == null) return;

            byte[] idMask = HmacSha256TruncReversed(Encoding.ASCII.GetBytes("DeviceID"), sharedSecret24, 2);
            byte[] idReq = new byte[8];
            idReq[0] = 0x0A;
            Array.Copy(uuidHash4, 0, idReq, 1, 4);
            idReq[5] = (byte)((assignedDeviceId & 0xFF) ^ idMask[0]);
            idReq[6] = (byte)(((assignedDeviceId >> 8) & 0xFF) ^ idMask[1]);
            if (SendMaspAndWait(idReq, 0x0B) == null) return;

            byte[] netKey16 = DeriveKey(passphrase, "MCP");
            byte[] keyMask = HmacSha256TruncReversed(Encoding.ASCII.GetBytes("NetworkKey"), sharedSecret24, 16);
            for (int h = 0; h < 2; h++)
            {
                byte[] kdReq = new byte[15];
                kdReq[0] = 0x0C;
                Array.Copy(uuidHash4, 0, kdReq, 1, 4);
                kdReq[5] = (byte)h;
                for (int i = 0; i < 8; i++) kdReq[6 + i] = (byte)(netKey16[h * 8 + i] ^ keyMask[h * 8 + i]);
                if (SendMaspAndWait(kdReq, 0x0D, h) == null) return;
            }
            AppendLog("Association MASP réussie (Clé 8888 & ID 0x8001) !");
        }

        [STAThread]
        static void Main()
        {
            Application.EnableVisualStyles();
            Application.SetCompatibleTextRenderingDefault(false);
            Application.Run(new StudioForm());
        }
    }
}
