using NAudio.CoreAudioApi;
using NAudio.Wave;
using System;
using System.Collections.ObjectModel;
using System.Diagnostics;
using System.Linq;
using System.Net;
using System.Net.Sockets;
using System.Text;
using System.Threading.Channels;
using System.Threading.Tasks;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Media;

namespace AudioStreamerPC
{
    public partial class MainWindow : Window
    {
        private const int UdpAudioPort = 50505;
        private const int UdpDiscoveryPort = 50506;
        private WasapiRecorder? recorder;
        private IPEndPoint? clientEndPoint;
        private Channel<byte[]> audioChannel = Channel.CreateUnbounded<byte[]>();
        private ObservableCollection<AppSession> activeSessions = new();
        
        private UdpClient audioUdpServer;
        private bool isStreaming = false;

        public MainWindow()
        {
            InitializeComponent();
            AppsListBox.ItemsSource = activeSessions;

            audioUdpServer = new UdpClient(UdpAudioPort);

            StartAudioCapture();
            
            Task.Run(ReceiveLoop);
            Task.Run(SendLoop);
            
            StartDiscoveryServer();
            
            var timer = new System.Timers.Timer(3000);
            timer.Elapsed += (s, e) => Dispatcher.Invoke(LoadAudioSessions);
            timer.Start();
            LoadAudioSessions();
        }

        private void StartAudioCapture()
        {
            recorder = new WasapiRecorderBuilder()
                .WithLoopbackCapture()
                .WithFormat(new WaveFormat(48000, 16, 2))
                .Build();

            recorder.DataAvailable += (buffer, flags, devicePos, qpcPos) =>
            {
                if (isStreaming)
                {
                    audioChannel.Writer.TryWrite(buffer.ToArray());
                }
            };
            recorder.StartRecording();
        }

        private async Task ReceiveLoop()
        {
            while (true)
            {
                try
                {
                    var result = await audioUdpServer.ReceiveAsync();
                    var message = Encoding.ASCII.GetString(result.Buffer);

                    if (message.StartsWith("START"))
                    {
                        clientEndPoint = result.RemoteEndPoint;
                        var deviceName = message.Contains(":") ? message.Substring(6) : "Android Device";
                        isStreaming = true;
                        
                        Dispatcher.Invoke(() =>
                        {
                            StatusText.Text = "Streaming Audio...";
                            StatusIndicator.Fill = new SolidColorBrush(Color.FromRgb(76, 175, 80)); // أخضر
                            DeviceNameText.Text = deviceName;
                        });
                    }
                    else if (message == "STOP")
                    {
                        clientEndPoint = null;
                        isStreaming = false;
                        
                        Dispatcher.Invoke(() =>
                        {
                            StatusText.Text = "Waiting for connection...";
                            StatusIndicator.Fill = new SolidColorBrush(Color.FromRgb(211, 47, 47)); // أحمر
                            DeviceNameText.Text = "None";
                        });
                    }
                }
                catch (Exception ex)
                {
                    Debug.WriteLine(ex.Message);
                }
            }
        }

        private async Task SendLoop()
        {
            while (true)
            {
                if (isStreaming && clientEndPoint != null)
                {
                    try
                    {
                        var readTask = audioChannel.Reader.ReadAsync().AsTask();
                        var delayTask = Task.Delay(1000);
                        var completedTask = await Task.WhenAny(readTask, delayTask);

                        if (completedTask == readTask)
                        {
                            var audioBuffer = await readTask;
                            await audioUdpServer.SendAsync(audioBuffer, audioBuffer.Length, clientEndPoint);
                        }
                        else
                        {
                            byte[] heartbeat = new byte[4]; 
                            await audioUdpServer.SendAsync(heartbeat, heartbeat.Length, clientEndPoint);
                        }
                    }
                    catch { }
                }
                else
                {
                    await Task.Delay(100);
                }
            }
        }

        private void StartDiscoveryServer()
        {
            Task.Run(async () =>
            {
                using var udp = new UdpClient(UdpDiscoveryPort);
                while (true)
                {
                    var result = await udp.ReceiveAsync();
                    var msg = Encoding.ASCII.GetString(result.Buffer);
                    if (msg == "DISCOVER_AUDIO_SERVER")
                    {
                        var response = Encoding.ASCII.GetBytes("AUDIO_SERVER_HERE");
                        await udp.SendAsync(response, response.Length, result.RemoteEndPoint);
                    }
                }
            });
        }

        private void LoadAudioSessions()
        {
            var enumerator = new MMDeviceEnumerator();
            var defaultDevice = enumerator.GetDefaultAudioEndpoint(DataFlow.Render, Role.Multimedia);
            var sessionManager = defaultDevice.AudioSessionManager;
            var sessions = sessionManager.Sessions;

            for (int i = 0; i < sessions.Count; i++)
            {
                var session = sessions[i];
                var processId = (uint)session.GetProcessID;
                if (processId == 0) continue;

                try
                {
                    var process = Process.GetProcessById((int)processId);
                    var displayName = process.ProcessName;
                    
                    displayName = char.ToUpper(displayName[0]) + displayName.Substring(1);

                    var existingSession = activeSessions.FirstOrDefault(s => s.ProcessId == processId);
                    
                    if (existingSession == null)
                    {
                        var appSession = new AppSession
                        {
                            ProcessId = processId,
                            DisplayName = displayName,
                            IsMuted = session.SimpleAudioVolume.Mute,
                            AudioSession = session
                        };
                        Dispatcher.Invoke(() => activeSessions.Add(appSession));
                    }
                    else
                    {
                        existingSession.AudioSession = session;
                    }
                }
                catch { }
            }
            
            var deadItems = activeSessions.Where(s => {
                try { Process.GetProcessById((int)s.ProcessId); return false; } 
                catch { return true; }
            }).ToList();

            foreach (var item in deadItems)
            {
                Dispatcher.Invoke(() => activeSessions.Remove(item));
            }
        }

        private void AppMute_Changed(object sender, RoutedEventArgs e)
        {
            if (sender is CheckBox checkBox && checkBox.DataContext is AppSession appSession)
            {
                try
                {
                    appSession.AudioSession.SimpleAudioVolume.Mute = checkBox.IsChecked ?? false;
                }
                catch { }
            }
        }

        private void RefreshApps_Click(object sender, RoutedEventArgs e)
        {
            LoadAudioSessions();
        }

        private void ForceDisconnect_Click(object sender, RoutedEventArgs e)
        {
            clientEndPoint = null;
            isStreaming = false;
            StatusText.Text = "Waiting for connection...";
            StatusIndicator.Fill = new SolidColorBrush(Color.FromRgb(211, 47, 47));
            DeviceNameText.Text = "None";
        }
    }

    public class AppSession
    {
        public uint ProcessId { get; set; }
        public string DisplayName { get; set; } = "";
        public bool IsMuted { get; set; }
        public AudioSessionControl AudioSession { get; set; } = null!;
    }
}