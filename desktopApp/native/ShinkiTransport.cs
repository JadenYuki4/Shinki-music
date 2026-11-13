using System;
using System.Text;
using System.Threading;
using System.Runtime.InteropServices;
using Windows.Media;
using Windows.Media.Playback;

// libmpv owns audio. This WinRT object publishes its transport state to Windows.
class ShinkiTransport {
    [StructLayout(LayoutKind.Sequential)]
    struct Message {
        public IntPtr window;
        public uint kind;
        public UIntPtr key;
        public IntPtr data;
        public uint time;
        public int x, y;
        public uint reserved;
    }
    [DllImport("user32")] static extern bool RegisterHotKey(IntPtr window, int id, uint modifiers, uint key);
    [DllImport("user32")] static extern bool UnregisterHotKey(IntPtr window, int id);
    [DllImport("user32")] static extern int GetMessageW(out Message message, IntPtr window, uint first, uint last);

    // Some Bluetooth drivers emit VK_MEDIA_* instead of sending an SMTC request.
    // Register only transport keys. Windows releases registrations when this thread exits.
    static void MediaKeys() {
        int[] keys = { 0xB3, 0xB0, 0xB1 };
        bool[] registered = new bool[keys.Length];
        try {
            for (int i = 0; i < keys.Length; i++)
                registered[i] = RegisterHotKey(IntPtr.Zero, keys[i], 0x4000, (uint)keys[i]);
            if (Array.IndexOf(registered, true) < 0) return;
            Message msg;
            while (GetMessageW(out msg, IntPtr.Zero, 0, 0) > 0) {
                if (msg.kind != 0x312) continue;
                switch (msg.key.ToUInt32()) {
                    case 0xB3: Console.WriteLine("PlayPause"); break;
                    case 0xB0: Console.WriteLine("Next"); break;
                    case 0xB1: Console.WriteLine("Previous"); break;
                }
            }
        } finally {
            for (int i = 0; i < keys.Length; i++)
                if (registered[i]) UnregisterHotKey(IntPtr.Zero, keys[i]);
        }
    }

    static void Main() {
        new Thread(MediaKeys) { IsBackground = true }.Start();
        using (var player = new MediaPlayer()) {
            player.CommandManager.IsEnabled = false;
            var controls = player.SystemMediaTransportControls;
            controls.IsPlayEnabled = controls.IsPauseEnabled = true;
            controls.IsNextEnabled = controls.IsPreviousEnabled = true;
            controls.ButtonPressed += (sender, args) => Console.WriteLine(args.Button.ToString());
            controls.IsEnabled = true;
            var display = controls.DisplayUpdater;
            display.Type = MediaPlaybackType.Music;
            display.AppMediaId = "Shinki music";
            Console.WriteLine("Ready");
            string line;
            while ((line = Console.ReadLine()) != null) {
                var fields = line.Split('\t');
                if (fields.Length != 3) continue;
                display.MusicProperties.Title = Decode(fields[1]);
                display.MusicProperties.Artist = Decode(fields[2]);
                display.Update();
                controls.PlaybackStatus = fields[0] == "Playing" ? MediaPlaybackStatus.Playing : MediaPlaybackStatus.Paused;
            }
            controls.IsEnabled = false;
        }
    }
    static string Decode(string value) { return Encoding.UTF8.GetString(Convert.FromBase64String(value)); }
}

