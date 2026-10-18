using System.Diagnostics;
using System.Management;
using System.Net;
using System.Net.Http.Headers;
using System.Net.NetworkInformation;
using System.Net.Sockets;
using System.Runtime.InteropServices;
using System.Reflection;
using System.ServiceProcess;
using System.Text;
using System.Text.Json;
using System.Text.RegularExpressions;

namespace AITSMCollector;

internal sealed record AgentConfig(string BaseUrl, string Token, string Scope, string[] PrivateCidrs, int IntervalSeconds, string? CollectorId = null);
internal sealed record RedeemResponse(string CollectorId, string Token, string Scope, string[] PrivateCidrs, int IntervalSeconds);
internal sealed record BridgeHeartbeatResponse(bool Enabled, string ConfiguredModel, int PollSeconds);
internal sealed record BridgeMessage(string Role, string Content);
internal sealed record BridgeJob(string JobId, string Model, BridgeMessage[] Messages, long TimeoutMillis);

internal static class Program
{
    internal const string ServiceName = "AITSMCollector";
    internal static readonly string InstallDir = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.CommonApplicationData), "AITSMCollector");
    internal static readonly string InstalledExe = Path.Combine(InstallDir, "AITSMCollector.exe");
    internal static readonly string ConfigPath = Path.Combine(InstallDir, "collector.dat");
    internal static readonly string LogPath = Path.Combine(InstallDir, "collector.log");
    internal static readonly string StatusPath = Path.Combine(InstallDir, "collector-status.json");
    internal static string AgentVersion => Assembly.GetExecutingAssembly().GetName().Version?.ToString(3) ?? "1.1.0";

    public static int Main(string[] args)
    {
        if (args.Contains("--service", StringComparer.OrdinalIgnoreCase))
        {
            ServiceBase.Run(new CollectorWindowsService());
            return 0;
        }
        if (args.Contains("--uninstall", StringComparer.OrdinalIgnoreCase)) return Uninstall();
        return Install().GetAwaiter().GetResult();
    }

    private static async Task<int> Install()
    {
        try
        {
            RequireAdministrator();
            var (baseUrl, code, repairCollectorId) = ReadBootstrapFromFilename(Environment.ProcessPath ?? "");
            Directory.CreateDirectory(InstallDir);
            AgentConfig? existing = null;
            if (File.Exists(ConfigPath))
            {
                existing = JsonSerializer.Deserialize<AgentConfig>(MachineProtection.Unprotect(File.ReadAllBytes(ConfigPath)), JsonOptions())
                    ?? throw new InvalidOperationException("Existing collector configuration is invalid. Use the uninstall option before reinstalling.");
            }

            var repairingSameCollector = existing != null &&
                existing.BaseUrl.Equals(baseUrl, StringComparison.OrdinalIgnoreCase) &&
                repairCollectorId != null &&
                existing.CollectorId != null &&
                existing.CollectorId.Equals(repairCollectorId, StringComparison.OrdinalIgnoreCase);
            if (repairingSameCollector)
            {
                Console.WriteLine("Repairing the existing AITSM Collector installation...");
            }
            else
            {
                Console.WriteLine(existing == null
                    ? "Enrolling this computer with AITSM..."
                    : existing.BaseUrl.Equals(baseUrl, StringComparison.OrdinalIgnoreCase)
                        ? "Registering this computer for the selected AITSM account..."
                        : $"Switching this computer from {existing.BaseUrl} to {baseUrl}...");
                var config = await Redeem(baseUrl, code);
                File.WriteAllBytes(ConfigPath, MachineProtection.Protect(JsonSerializer.SerializeToUtf8Bytes(config)));
            }
            RestrictInstallDirectory();
            RunSc("stop", ServiceName, ignoreFailure: true);
            WaitForServiceStop();
            RunSc("delete", ServiceName, ignoreFailure: true);
            File.Copy(Environment.ProcessPath!, InstalledExe, true);
            RunSc("create", ServiceName, "binPath=", $"\"{InstalledExe}\" --service", "start=", "auto", "DisplayName=", "AITSM Windows Collector");
            RunSc("config", ServiceName, "start=", "delayed-auto");
            RunSc("description", ServiceName, "Securely reports this computer's inventory and telemetry to AITSM.");
            RunSc("failure", ServiceName, "reset=", "86400", "actions=", "restart/60000/restart/60000/restart/60000");
            RunSc("failureflag", ServiceName, "1");
            RunSc("start", ServiceName);
            Console.WriteLine($"AITSM Collector {AgentVersion} installed and started successfully for {baseUrl}.");
            Console.WriteLine("This window can now be closed.");
            return 0;
        }
        catch (Exception error)
        {
            Console.Error.WriteLine($"Installation failed: {error.Message}");
            Console.Error.WriteLine("Press Enter to close.");
            Console.ReadLine();
            return 1;
        }
    }

    private static async Task<AgentConfig> Redeem(string baseUrl, string code)
    {
        using var http = new HttpClient { Timeout = TimeSpan.FromSeconds(30) };
        var payload = JsonSerializer.Serialize(new { enrollmentCode = code });
        using var response = await http.PostAsync($"{baseUrl}/api/monitoring/collector/redeem", new StringContent(payload, Encoding.UTF8, "application/json"));
        var json = await response.Content.ReadAsStringAsync();
        if (!response.IsSuccessStatusCode)
            throw new InvalidOperationException($"Enrollment failed (HTTP {(int)response.StatusCode}). The download may be expired or already used.");
        var enrollment = JsonSerializer.Deserialize<RedeemResponse>(json, JsonOptions())
            ?? throw new InvalidOperationException("Enrollment response was invalid.");
        return new AgentConfig(baseUrl, enrollment.Token, enrollment.Scope, enrollment.PrivateCidrs ?? [], Math.Max(30, enrollment.IntervalSeconds), enrollment.CollectorId);
    }

    private static int Uninstall()
    {
        try
        {
            RequireAdministrator();
            RunSc("stop", ServiceName, ignoreFailure: true);
            RunSc("delete", ServiceName, ignoreFailure: true);
            if (File.Exists(ConfigPath)) File.Delete(ConfigPath);
            if (File.Exists(StatusPath)) File.Delete(StatusPath);
            Console.WriteLine("AITSM Collector service removed. Revoke its entry in AITSM to invalidate the server token.");
            return 0;
        }
        catch (Exception error) { Console.Error.WriteLine(error.Message); return 1; }
    }

    private static (string BaseUrl, string Code, string? RepairCollectorId) ReadBootstrapFromFilename(string executable)
    {
        var name = Path.GetFileNameWithoutExtension(executable);
        var parts = name.Split("--", StringSplitOptions.None);
        if (parts.Length is not (3 or 4) || !parts[0].Equals("AITSMCollectorSetup", StringComparison.OrdinalIgnoreCase))
            throw new InvalidOperationException("This installer is missing its enrollment information. Download a fresh copy from AITSM.");
        var origin = Encoding.UTF8.GetString(Convert.FromBase64String(PadBase64(parts[1].Replace('-', '+').Replace('_', '/'))));
        if (!Uri.TryCreate(origin, UriKind.Absolute, out var uri) || uri.Scheme != Uri.UriSchemeHttps || uri.PathAndQuery != "/")
            throw new InvalidOperationException("The AITSM address embedded in this installer is invalid.");
        var repairCollectorId = parts.Length == 4 && !parts[3].Equals("new", StringComparison.OrdinalIgnoreCase)
            ? parts[3]
            : null;
        if (repairCollectorId != null && !Guid.TryParse(repairCollectorId, out _))
            throw new InvalidOperationException("The collector repair identity embedded in this installer is invalid.");
        return (origin.TrimEnd('/'), parts[2], repairCollectorId);
    }

    private static string PadBase64(string value) => value.PadRight(value.Length + ((4 - value.Length % 4) % 4), '=');
    private static void RequireAdministrator()
    {
        using var identity = System.Security.Principal.WindowsIdentity.GetCurrent();
        var principal = new System.Security.Principal.WindowsPrincipal(identity);
        if (!principal.IsInRole(System.Security.Principal.WindowsBuiltInRole.Administrator))
            throw new UnauthorizedAccessException("Right-click the installer and select Run as administrator.");
    }
    private static void RunSc(params string[] arguments) => RunSc(arguments[0], arguments[1], false, arguments.Skip(2).ToArray());
    private static void RunSc(string verb, string name, bool ignoreFailure, params string[] rest)
    {
        var psi = new ProcessStartInfo("sc.exe") { UseShellExecute = false, CreateNoWindow = true };
        psi.ArgumentList.Add(verb); psi.ArgumentList.Add(name);
        foreach (var value in rest) psi.ArgumentList.Add(value);
        using var process = Process.Start(psi) ?? throw new InvalidOperationException("Unable to run Windows service manager.");
        process.WaitForExit();
        if (!ignoreFailure && process.ExitCode != 0) throw new InvalidOperationException($"Windows service setup failed during '{verb}'.");
    }
    private static void RestrictInstallDirectory()
    {
        var psi = new ProcessStartInfo("icacls.exe") { UseShellExecute = false, CreateNoWindow = true };
        foreach (var value in new[] { InstallDir, "/inheritance:r", "/grant:r", "*S-1-5-18:(OI)(CI)F", "*S-1-5-32-544:(OI)(CI)F" })
            psi.ArgumentList.Add(value);
        using var process = Process.Start(psi) ?? throw new InvalidOperationException("Unable to secure the collector folder.");
        process.WaitForExit();
        if (process.ExitCode != 0) throw new InvalidOperationException("Windows could not secure the collector credential folder.");
    }
    private static void WaitForServiceStop()
    {
        try
        {
            using var service = new ServiceController(ServiceName);
            if (service.Status != ServiceControllerStatus.Stopped)
                service.WaitForStatus(ServiceControllerStatus.Stopped, TimeSpan.FromSeconds(20));
        }
        catch (InvalidOperationException) { }
        catch (System.ComponentModel.Win32Exception) { }
    }
    internal static JsonSerializerOptions JsonOptions() => new() { PropertyNameCaseInsensitive = true, PropertyNamingPolicy = JsonNamingPolicy.CamelCase };
    internal static AgentConfig LoadConfig() => JsonSerializer.Deserialize<AgentConfig>(MachineProtection.Unprotect(File.ReadAllBytes(ConfigPath)), JsonOptions())
        ?? throw new InvalidOperationException("Collector configuration is invalid.");
    internal static void Log(string message)
    {
        try
        {
            Directory.CreateDirectory(InstallDir);
            if (File.Exists(LogPath) && new FileInfo(LogPath).Length > 1_048_576) File.Move(LogPath, LogPath + ".old", true);
            File.AppendAllText(LogPath, $"{DateTimeOffset.UtcNow:o} {message}{Environment.NewLine}");
        }
        catch { }
    }
    internal static void WriteStatus(string state, string baseUrl, DateTimeOffset startedAt, int consecutiveFailures, string? lastError, DateTimeOffset? lastSuccess)
    {
        try
        {
            Directory.CreateDirectory(InstallDir);
            var status = JsonSerializer.Serialize(new {
                state, agentVersion = AgentVersion, target = baseUrl, serviceStartedAt = startedAt,
                checkedAt = DateTimeOffset.UtcNow, lastSuccessAt = lastSuccess,
                consecutiveFailures, lastError
            }, JsonOptions());
            var temporary = StatusPath + ".tmp";
            File.WriteAllText(temporary, status);
            File.Move(temporary, StatusPath, true);
        }
        catch { }
    }
}

internal sealed class CollectorWindowsService : ServiceBase
{
    private CancellationTokenSource? cancellation;
    public CollectorWindowsService() { ServiceName = Program.ServiceName; CanStop = true; AutoLog = true; }
    protected override void OnStart(string[] args)
    {
        cancellation = new CancellationTokenSource();
        Program.Log($"Collector service {Program.AgentVersion} started.");
        _ = Task.Run(() => CollectorLoop.Run(cancellation.Token));
        _ = Task.Run(() => AiBridgeLoop.Run(cancellation.Token));
    }
    protected override void OnStop() { cancellation?.Cancel(); Program.Log("Collector service stopped."); }
}

internal static class CollectorLoop
{
    public static async Task Run(CancellationToken stoppingToken)
    {
        var startedAt = DateTimeOffset.UtcNow;
        var consecutiveFailures = 0;
        string? previousError = null;
        DateTimeOffset? lastSuccess = null;
        while (!stoppingToken.IsCancellationRequested)
        {
            var delay = 60;
            string target = "unknown";
            try
            {
                var config = Program.LoadConfig();
                delay = Math.Max(30, config.IntervalSeconds);
                target = config.BaseUrl;
                await Report(config, startedAt, previousError, stoppingToken);
                consecutiveFailures = 0;
                previousError = null;
                lastSuccess = DateTimeOffset.UtcNow;
                Program.WriteStatus("online", target, startedAt, consecutiveFailures, null, lastSuccess);
            }
            catch (Exception error)
            {
                consecutiveFailures++;
                previousError = Regex.Replace(error.Message, "[\\r\\n]+", " ");
                if (previousError.Length > 500) previousError = previousError[..500];
                delay = Math.Min(900, delay * (1 << Math.Min(consecutiveFailures, 4)));
                Program.Log($"Collection cycle failed ({consecutiveFailures}); retrying in {delay}s: {previousError}");
                Program.WriteStatus("error", target, startedAt, consecutiveFailures, previousError, lastSuccess);
            }
            try { await Task.Delay(TimeSpan.FromSeconds(delay), stoppingToken); } catch (OperationCanceledException) { }
        }
    }

    private static async Task Report(AgentConfig config, DateTimeOffset serviceStartedAt, string? previousError, CancellationToken token)
    {
        using var http = new HttpClient { Timeout = TimeSpan.FromSeconds(30) };
        http.DefaultRequestHeaders.Authorization = new AuthenticationHeaderValue("Bearer", config.Token);
        string? discoveryId = null;
        if (config.Scope == "LAN")
        {
            using var command = await http.GetAsync($"{config.BaseUrl}/api/monitoring/collector/commands", token);
            if (command.IsSuccessStatusCode && command.StatusCode != HttpStatusCode.NoContent)
            {
                using var document = JsonDocument.Parse(await command.Content.ReadAsStringAsync(token));
                discoveryId = document.RootElement.TryGetProperty("requestId", out var id) ? id.GetString() : null;
            }
        }
        var devices = new List<object> { InventoryReader.ReadHost() };
        if (config.Scope == "LAN") devices.AddRange(InventoryReader.ReadPeers(config.PrivateCidrs));
        var payload = JsonSerializer.Serialize(new {
            measuredAt = DateTimeOffset.UtcNow, devices, discoveryRequestId = discoveryId, discoveryError = (string?)null,
            agentVersion = Program.AgentVersion, serviceStartedAt, previousError, consecutiveFailures = 0
        }, Program.JsonOptions());
        using var response = await http.PostAsync($"{config.BaseUrl}/api/monitoring/collector/reports", new StringContent(payload, Encoding.UTF8, "application/json"), token);
        if (!response.IsSuccessStatusCode)
        {
            var detail = await response.Content.ReadAsStringAsync(token);
            detail = Regex.Replace(detail, "[\\r\\n]+", " ");
            if (detail.Length > 500) detail = detail[..500];
            throw new InvalidOperationException($"Server rejected report with HTTP {(int)response.StatusCode}: {detail}");
        }
    }
}

internal static class AiBridgeLoop
{
    public static async Task Run(CancellationToken stoppingToken)
    {
        var enabled = false;
        var available = false;
        var nextHeartbeat = DateTimeOffset.MinValue;
        while (!stoppingToken.IsCancellationRequested)
        {
            var delaySeconds = 5;
            try
            {
                var config = Program.LoadConfig();
                if (!config.Scope.Equals("LAN", StringComparison.OrdinalIgnoreCase))
                {
                    await Delay(delaySeconds, stoppingToken);
                    continue;
                }
                if (DateTimeOffset.UtcNow >= nextHeartbeat)
                {
                    var bootstrap = await Heartbeat(config, false, null, null, stoppingToken);
                    enabled = bootstrap.Enabled;
                    if (enabled)
                    {
                        var probe = await ProbeOllama(bootstrap.ConfiguredModel, stoppingToken);
                        available = probe.Available;
                        var state = await Heartbeat(config, available, bootstrap.ConfiguredModel, probe.Error, stoppingToken);
                        enabled = state.Enabled;
                        delaySeconds = Math.Clamp(state.PollSeconds, 2, 30);
                    }
                    else available = false;
                    nextHeartbeat = DateTimeOffset.UtcNow.AddSeconds(30);
                }
                if (enabled && available) { delaySeconds = 2; await ProcessOneJob(config, stoppingToken); }
            }
            catch (OperationCanceledException) when (stoppingToken.IsCancellationRequested) { }
            catch (Exception error)
            {
                Program.Log($"AI bridge cycle failed: {Clean(error.Message)}");
                delaySeconds = 15;
            }
            await Delay(delaySeconds, stoppingToken);
        }
    }

    private static async Task<BridgeHeartbeatResponse> Heartbeat(AgentConfig config, bool available, string? model, string? error, CancellationToken token)
    {
        using var http = CloudClient(config, TimeSpan.FromSeconds(20));
        var payload = JsonSerializer.Serialize(new { available, model, agentVersion = Program.AgentVersion, error = error == null ? null : Clean(error) }, Program.JsonOptions());
        using var response = await http.PostAsync($"{config.BaseUrl}/api/ai/bridge/collector/heartbeat", new StringContent(payload, Encoding.UTF8, "application/json"), token);
        response.EnsureSuccessStatusCode();
        return JsonSerializer.Deserialize<BridgeHeartbeatResponse>(await response.Content.ReadAsStringAsync(token), Program.JsonOptions())
            ?? throw new InvalidOperationException("AI bridge heartbeat response was invalid.");
    }

    private static async Task<(bool Available, string? Error)> ProbeOllama(string model, CancellationToken token)
    {
        try
        {
            using var http = new HttpClient { BaseAddress = new Uri("http://127.0.0.1:11434"), Timeout = TimeSpan.FromSeconds(5) };
            using var response = await http.GetAsync("/api/tags", token);
            response.EnsureSuccessStatusCode();
            using var document = JsonDocument.Parse(await response.Content.ReadAsStringAsync(token));
            var found = document.RootElement.GetProperty("models").EnumerateArray().Any(item =>
                item.TryGetProperty("name", out var name) && string.Equals(name.GetString(), model, StringComparison.OrdinalIgnoreCase));
            return found ? (true, null) : (false, $"Ollama model '{model}' is not installed. Run: ollama pull {model}");
        }
        catch (Exception error) { return (false, $"Ollama is unavailable on 127.0.0.1:11434: {Clean(error.Message)}"); }
    }

    private static async Task ProcessOneJob(AgentConfig config, CancellationToken token)
    {
        using var cloud = CloudClient(config, TimeSpan.FromSeconds(20));
        using var response = await cloud.GetAsync($"{config.BaseUrl}/api/ai/bridge/collector/jobs/next", token);
        if (response.StatusCode == HttpStatusCode.NoContent) return;
        response.EnsureSuccessStatusCode();
        var job = JsonSerializer.Deserialize<BridgeJob>(await response.Content.ReadAsStringAsync(token), Program.JsonOptions())
            ?? throw new InvalidOperationException("AI bridge job was invalid.");
        try
        {
            var answer = await RunOllama(job, token);
            await Complete(cloud, config, job.JobId, true, answer, null, token);
        }
        catch (Exception error)
        {
            await Complete(cloud, config, job.JobId, false, null, Clean(error.Message), token);
        }
    }

    private static async Task<string> RunOllama(BridgeJob job, CancellationToken token)
    {
        using var ollama = new HttpClient { BaseAddress = new Uri("http://127.0.0.1:11434"), Timeout = TimeSpan.FromMilliseconds(Math.Clamp(job.TimeoutMillis, 5_000, 120_000)) };
        var payload = JsonSerializer.Serialize(new { model = job.Model, messages = job.Messages, stream = false }, Program.JsonOptions());
        using var response = await ollama.PostAsync("/api/chat", new StringContent(payload, Encoding.UTF8, "application/json"), token);
        response.EnsureSuccessStatusCode();
        using var document = JsonDocument.Parse(await response.Content.ReadAsStringAsync(token));
        var content = document.RootElement.GetProperty("message").GetProperty("content").GetString()?.Trim();
        if (string.IsNullOrWhiteSpace(content)) throw new InvalidOperationException("Ollama returned an empty response.");
        return content.Length <= 100_000 ? content : content[..100_000];
    }

    private static async Task Complete(HttpClient http, AgentConfig config, string jobId, bool success, string? content, string? error, CancellationToken token)
    {
        var payload = JsonSerializer.Serialize(new { success, content, error }, Program.JsonOptions());
        using var response = await http.PostAsync($"{config.BaseUrl}/api/ai/bridge/collector/jobs/{Uri.EscapeDataString(jobId)}/complete", new StringContent(payload, Encoding.UTF8, "application/json"), token);
        response.EnsureSuccessStatusCode();
    }

    private static HttpClient CloudClient(AgentConfig config, TimeSpan timeout)
    {
        var http = new HttpClient { Timeout = timeout };
        http.DefaultRequestHeaders.Authorization = new AuthenticationHeaderValue("Bearer", config.Token);
        return http;
    }
    private static string Clean(string value)
    {
        var clean = Regex.Replace(value, "[\\r\\n]+", " ").Trim();
        return clean.Length > 500 ? clean[..500] : clean;
    }
    private static async Task Delay(int seconds, CancellationToken token) { try { await Task.Delay(TimeSpan.FromSeconds(seconds), token); } catch (OperationCanceledException) { } }
}

internal static class InventoryReader
{
    public static object ReadHost()
    {
        var computer = First("SELECT Manufacturer,Model,TotalPhysicalMemory,Domain,PartOfDomain FROM Win32_ComputerSystem");
        var product = First("SELECT UUID FROM Win32_ComputerSystemProduct");
        var os = First("SELECT Caption,Version,BuildNumber,TotalVisibleMemorySize,FreePhysicalMemory,InstallDate FROM Win32_OperatingSystem");
        var processors = All("SELECT Name,MaxClockSpeed,LoadPercentage FROM Win32_Processor");
        var disks = All("SELECT DeviceID,Size,FreeSpace FROM Win32_LogicalDisk WHERE DriveType=3");
        var videos = All("SELECT Name,AdapterRAM FROM Win32_VideoController");
        var adapter = NetworkInterface.GetAllNetworkInterfaces().First(item => item.OperationalStatus == OperationalStatus.Up &&
            item.NetworkInterfaceType != NetworkInterfaceType.Loopback && item.GetIPProperties().UnicastAddresses.Any(a => a.Address.AddressFamily == AddressFamily.InterNetwork && IsPrivate(a.Address)));
        var ip = adapter.GetIPProperties().UnicastAddresses.First(a => a.Address.AddressFamily == AddressFamily.InterNetwork && IsPrivate(a.Address)).Address.ToString();
        var total = disks.Sum(d => Long(d, "Size")); var free = disks.Sum(d => Long(d, "FreeSpace"));
        var memoryTotalKb = Long(os, "TotalVisibleMemorySize"); var memoryFreeKb = Long(os, "FreePhysicalMemorySize", Long(os, "FreePhysicalMemory"));
        var cpu = processors.Count == 0 ? 0 : processors.Average(p => Double(p, "LoadPercentage"));
        var inventory = new {
            fullDeviceName = Environment.MachineName, manufacturer = Text(computer, "Manufacturer"), model = Text(computer, "Model"),
            processorName = string.Join("; ", processors.Select(p => Text(p, "Name")).Where(v => v.Length > 0).Distinct()),
            processorSpeed = processors.Count == 0 ? null : $"{processors.Max(p => Double(p, "MaxClockSpeed")) / 1000:0.##} GHz",
            installedRam = Bytes(memoryTotalKb * 1024), usableRam = Bytes(memoryTotalKb * 1024), ramSpeed = (string?)null,
            gpuName = string.Join("; ", videos.Select(v => Text(v, "Name")).Where(v => v.Length > 0).Distinct()), gpuMemory = Bytes(videos.Sum(v => Long(v, "AdapterRAM"))),
            storageTotal = Bytes(total), storageUsed = Bytes(total - free), storageFree = Bytes(free),
            storageBreakdownJson = JsonSerializer.Serialize(disks.Select(d => new { drive = Text(d, "DeviceID"), total = Bytes(Long(d, "Size")), used = Bytes(Long(d, "Size") - Long(d, "FreeSpace")), free = Bytes(Long(d, "FreeSpace")) })),
            systemType = RuntimeInformation.OSArchitecture.ToString(), osName = Text(os, "Caption"), osEdition = Text(os, "Caption"), osVersion = Text(os, "Version"), osBuild = Text(os, "BuildNumber"),
            installedOn = Text(os, "InstallDate"), domainOrWorkgroup = Text(computer, "Domain"), deviceUuid = Text(product, "UUID"), productId = (string?)null, penTouchSupport = (string?)null
        };
        return new {
            deviceKey = $"host:{Environment.MachineName.ToLowerInvariant()}", hostname = Environment.MachineName, ipAddress = ip,
            macAddress = string.Join("-", adapter.GetPhysicalAddress().GetAddressBytes().Select(b => b.ToString("X2"))),
            osDetails = $"{Text(os, "Caption")} {Text(os, "Version")}", reachable = true, telemetryAvailable = true,
            cpuUsagePercent = Math.Round(cpu, 2), memoryUsagePercent = memoryTotalKb == 0 ? 0 : Math.Round(100d * (memoryTotalKb - memoryFreeKb) / memoryTotalKb, 2),
            storageTotalBytes = total, storageUsedBytes = Math.Max(0, total - free), measurementAt = DateTimeOffset.UtcNow, inventory
        };
    }

    public static IEnumerable<object> ReadPeers(string[] cidrs)
    {
        var output = Run("arp.exe", "-a");
        var regex = new Regex(@"^\s*(?<ip>\d{1,3}(?:\.\d{1,3}){3})\s+(?<mac>[0-9a-f-]{17})\s+", RegexOptions.IgnoreCase | RegexOptions.Multiline);
        foreach (Match match in regex.Matches(output))
        {
            var ip = match.Groups["ip"].Value;
            if (!IPAddress.TryParse(ip, out var address) || !IsPrivate(address) || !cidrs.Any(c => InCidr(address, c))) continue;
            string hostname; try { hostname = Dns.GetHostEntry(address).HostName; } catch { hostname = "LAN peer"; }
            yield return new { deviceKey = $"peer:{ip}", hostname, ipAddress = ip, macAddress = match.Groups["mac"].Value, osDetails = (string?)null, reachable = true, telemetryAvailable = false, cpuUsagePercent = (double?)null, memoryUsagePercent = (double?)null, storageTotalBytes = (long?)null, storageUsedBytes = (long?)null, measurementAt = DateTimeOffset.UtcNow, inventory = (object?)null };
        }
    }
    private static Dictionary<string, object?> First(string query) => All(query).FirstOrDefault() ?? [];
    private static List<Dictionary<string, object?>> All(string query)
    {
        var result = new List<Dictionary<string, object?>>();
        using var searcher = new ManagementObjectSearcher(query);
        foreach (ManagementObject item in searcher.Get())
        {
            var row = new Dictionary<string, object?>(StringComparer.OrdinalIgnoreCase);
            foreach (PropertyData property in item.Properties) row[property.Name] = property.Value;
            result.Add(row); item.Dispose();
        }
        return result;
    }
    private static string Text(Dictionary<string, object?> row, string key) => row.TryGetValue(key, out var value) ? Convert.ToString(value)?.Trim() ?? "" : "";
    private static long Long(Dictionary<string, object?> row, string key, long fallback = 0) => row.TryGetValue(key, out var value) && long.TryParse(Convert.ToString(value), out var parsed) ? parsed : fallback;
    private static double Double(Dictionary<string, object?> row, string key) => row.TryGetValue(key, out var value) && double.TryParse(Convert.ToString(value), out var parsed) ? parsed : 0;
    private static string? Bytes(long value) => value <= 0 ? null : value >= 1L << 40 ? $"{value / (double)(1L << 40):0.##} TB" : $"{value / (double)(1L << 30):0.##} GB";
    private static string Run(string file, string args) { using var p = Process.Start(new ProcessStartInfo(file, args) { RedirectStandardOutput = true, UseShellExecute = false, CreateNoWindow = true }); return p?.StandardOutput.ReadToEnd() ?? ""; }
    private static bool IsPrivate(IPAddress ip) { var b = ip.GetAddressBytes(); return b.Length == 4 && (b[0] == 10 || b[0] == 172 && b[1] is >= 16 and <= 31 || b[0] == 192 && b[1] == 168); }
    private static bool InCidr(IPAddress ip, string cidr)
    {
        var parts = cidr.Split('/'); if (parts.Length != 2 || !IPAddress.TryParse(parts[0], out var network) || !int.TryParse(parts[1], out var prefix)) return false;
        var a = ip.GetAddressBytes(); var n = network.GetAddressBytes(); if (a.Length != 4 || n.Length != 4 || prefix is < 0 or > 32) return false;
        for (var i = 0; i < 4; i++) { var bits = Math.Min(8, Math.Max(0, prefix - i * 8)); var mask = bits == 0 ? 0 : 0xff << (8 - bits) & 0xff; if ((a[i] & mask) != (n[i] & mask)) return false; }
        return true;
    }
}

internal static class MachineProtection
{
    [StructLayout(LayoutKind.Sequential)] private struct Blob { public int Size; public IntPtr Data; }
    [DllImport("crypt32.dll", SetLastError = true)] private static extern bool CryptProtectData(ref Blob input, string? description, IntPtr entropy, IntPtr reserved, IntPtr prompt, int flags, out Blob output);
    [DllImport("crypt32.dll", SetLastError = true)] private static extern bool CryptUnprotectData(ref Blob input, IntPtr description, IntPtr entropy, IntPtr reserved, IntPtr prompt, int flags, out Blob output);
    [DllImport("kernel32.dll")] private static extern IntPtr LocalFree(IntPtr memory);
    private const int LocalMachine = 0x4;
    public static byte[] Protect(byte[] plain) => Transform(plain, true);
    public static byte[] Unprotect(byte[] cipher) => Transform(cipher, false);
    private static byte[] Transform(byte[] inputBytes, bool protect)
    {
        var input = new Blob { Size = inputBytes.Length, Data = Marshal.AllocHGlobal(inputBytes.Length) };
        try
        {
            Marshal.Copy(inputBytes, 0, input.Data, inputBytes.Length);
            Blob output;
            var ok = protect ? CryptProtectData(ref input, "AITSM Collector", IntPtr.Zero, IntPtr.Zero, IntPtr.Zero, LocalMachine, out output)
                : CryptUnprotectData(ref input, IntPtr.Zero, IntPtr.Zero, IntPtr.Zero, IntPtr.Zero, LocalMachine, out output);
            if (!ok) throw new InvalidOperationException($"Windows credential protection failed ({Marshal.GetLastWin32Error()}).");
            try { var result = new byte[output.Size]; Marshal.Copy(output.Data, result, 0, output.Size); return result; }
            finally { LocalFree(output.Data); }
        }
        finally { Marshal.FreeHGlobal(input.Data); }
    }
}
