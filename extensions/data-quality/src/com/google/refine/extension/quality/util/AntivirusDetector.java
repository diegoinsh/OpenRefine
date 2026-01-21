package com.google.refine.extension.quality.util;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Utility class to detect antivirus software on the system.
 */
public class AntivirusDetector {

    private static final Logger logger = LoggerFactory.getLogger(AntivirusDetector.class);

    private static final String[] COMMON_ANTIVIRUS_PROCESSES = {
        "msmpeng.exe", "MsMpEng.exe",
        "avast.exe", "avastui.exe",
        "avgui.exe", "avgsvc.exe", "avgnsa.exe",
        "bdagent.exe", "vsserv.exe",
        "egui.exe", "ekrn.exe",
        "kav.exe", "kavmm.exe",
        "mcuicnt.exe", "mcshield.exe",
        "avp.exe",
        "savservice.exe", "savscan.exe",
        "sophosav.exe",
        "tmccuf.exe", "tmbmsrv.exe",
        "webrootsecureanywhere.exe",
        "zapro.exe", "vsmon.exe"
    };

    public static class AntivirusInfo {
        public String name;
        public String version;
        public String status;

        public AntivirusInfo(String name, String version, String status) {
            this.name = name;
            this.version = version;
            this.status = status;
        }

        @Override
        public String toString() {
            return String.format("%s (版本: %s, 状态: %s)", name, version, status);
        }
    }

    public static List<AntivirusInfo> detectAntivirus() {
        List<AntivirusInfo> result = new ArrayList<>();

        String osName = System.getProperty("os.name").toLowerCase();
        if (osName.contains("win")) {
            result.addAll(detectAntivirusWindows());
        } else if (osName.contains("linux") || osName.contains("mac")) {
            result.addAll(detectAntivirusUnix());
        }

        return result;
    }

    private static List<AntivirusInfo> detectAntivirusWindows() {
        List<AntivirusInfo> result = new ArrayList<>();

        try {
            logger.info("尝试通过 PowerShell 检测杀毒软件...");
            result.addAll(detectViaPowerShell());
            logger.info("PowerShell 检测到 {} 个杀毒软件", result.size());
        } catch (Exception e) {
            logger.warn("Failed to detect antivirus via PowerShell", e);
        }

        if (result.isEmpty()) {
            try {
                logger.info("PowerShell 检测无结果，尝试通过 WMI 检测...");
                result.addAll(detectViaWMI());
                logger.info("WMI 检测到 {} 个杀毒软件", result.size());
            } catch (Exception e) {
                logger.warn("Failed to detect antivirus via WMI", e);
            }
        }

        if (result.isEmpty()) {
            try {
                logger.info("WMI 检测无结果，尝试通过注册表检测...");
                result.addAll(detectViaRegistry());
                logger.info("注册表检测到 {} 个杀毒软件", result.size());
            } catch (Exception e) {
                logger.warn("Failed to detect antivirus via registry", e);
            }
        }

        if (result.isEmpty()) {
            try {
                logger.info("注册表检测无结果，尝试通过进程检测...");
                result.addAll(detectViaProcess());
                logger.info("进程检测到 {} 个杀毒软件", result.size());
            } catch (Exception e) {
                logger.warn("Failed to detect antivirus via process", e);
            }
        }

        return result;
    }

    private static List<AntivirusInfo> detectViaPowerShell() {
        List<AntivirusInfo> result = new ArrayList<>();

        try {
            String command = "Get-WmiObject -Namespace root\\SecurityCenter2 -Class AntiVirusProduct | Select-Object displayName, version, productState | ConvertTo-Json";
            ProcessBuilder pb = new ProcessBuilder("powershell", "-Command", command);
            pb.redirectErrorStream(true);
            Process process = pb.start();

            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), "GBK"));
            StringBuilder output = new StringBuilder();
            String line;

            while ((line = reader.readLine()) != null) {
                output.append(line);
            }

            process.waitFor();
            reader.close();

            String jsonOutput = output.toString().trim();
            logger.info("PowerShell 输出: {}", jsonOutput);

            if (!jsonOutput.isEmpty()) {
                jsonOutput = jsonOutput.replace("\n", "").replace("\r", "");
                
                Pattern pattern = Pattern.compile("\\{\\s*\"displayName\"\\s*:\\s*\"([^\"]+)\"\\s*,\\s*\"version\"\\s*:\\s*(null|\"[^\"]*\")\\s*,\\s*\"productState\"\\s*:\\s*(\\d+)\\s*\\}");
                Matcher matcher = pattern.matcher(jsonOutput);

                while (matcher.find()) {
                    String name = matcher.group(1);
                    String version = matcher.group(2);
                    String productState = matcher.group(3);
                    
                    if ("null".equals(version)) {
                        version = "未知";
                    } else {
                        version = version.replace("\"", "");
                    }
                    
                    String status = decodeProductState(productState);

                    result.add(new AntivirusInfo(name, version, status));
                    logger.info("检测到杀毒软件: {} (版本: {}, 状态: {})", name, version, status);
                }
            }

        } catch (Exception e) {
            logger.error("Error executing PowerShell command", e);
        }

        return result;
    }

    private static List<AntivirusInfo> detectViaWMI() {
        List<AntivirusInfo> result = new ArrayList<>();

        try {
            ProcessBuilder pb = new ProcessBuilder("wmic", "/namespace:\\\\root\\SecurityCenter2", "path", "AntiVirusProduct", "get", "displayName", "version", "productState");
            pb.redirectErrorStream(true);
            Process process = pb.start();

            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), "GBK"));
            String line;
            boolean headerSkipped = false;

            logger.info("开始解析 WMI 输出...");

            while ((line = reader.readLine()) != null) {
                line = line.trim();
                logger.debug("WMI 输出行: {}", line);
                
                if (line.isEmpty()) continue;

                if (!headerSkipped) {
                    headerSkipped = true;
                    logger.info("WMI 表头: {}", line);
                    continue;
                }

                String[] parts = line.split("\\s+", 3);
                logger.debug("解析部分: displayName={}, version={}, productState={}", 
                    parts.length >= 1 ? parts[0] : "N/A",
                    parts.length >= 2 ? parts[1] : "N/A",
                    parts.length >= 3 ? parts[2] : "N/A");

                if (parts.length >= 1 && !parts[0].isEmpty()) {
                    String name = parts[0];
                    String version = parts.length >= 2 ? parts[1] : "未知";
                    String status = decodeProductState(parts.length >= 3 ? parts[2] : "");
                    result.add(new AntivirusInfo(name, version, status));
                    logger.info("检测到杀毒软件: {} (版本: {}, 状态: {})", name, version, status);
                }
            }

            process.waitFor();
            reader.close();

        } catch (Exception e) {
            logger.error("Error executing WMI query", e);
        }

        return result;
    }

    private static String decodeProductState(String productState) {
        if (productState == null || productState.isEmpty()) {
            return "未知";
        }

        try {
            int state = Integer.parseInt(productState);
            logger.info("解析 productState: {} (十进制), {} (十六进制)", state, Integer.toHexString(state));
            
            int enabled = (state >> 2) & 0x3;
            int upToDate = (state >> 4) & 0x3;
            
            logger.info("启用状态位: {} (第2-3位), 病毒库状态位: {} (第4-5位)", enabled, upToDate);

            String status = "";
            if (enabled == 1) {
                status += "已启用";
            } else if (enabled == 0) {
                status += "已禁用";
            } else {
                status += "状态未知";
            }

            if (upToDate == 0) {
                status += ", 病毒库已更新";
            } else if (upToDate == 1) {
                status += ", 病毒库未更新";
            } else {
                status += ", 病毒库状态未知";
            }

            return status;
        } catch (NumberFormatException e) {
            return "未知";
        }
    }

    private static List<AntivirusInfo> detectViaRegistry() {
        List<AntivirusInfo> result = new ArrayList<>();

        try {
            ProcessBuilder pb = new ProcessBuilder("reg", "query",
                "HKEY_LOCAL_MACHINE\\SOFTWARE\\Microsoft\\Windows\\CurrentVersion\\Run",
                "/s");
            pb.redirectErrorStream(true);
            Process process = pb.start();

            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), "GBK"));
            String line;
            Pattern pattern = Pattern.compile(".*\\\\(.*?)\\\\.*");

            while ((line = reader.readLine()) != null) {
                Matcher matcher = pattern.matcher(line);
                if (matcher.find()) {
                    String key = matcher.group(1);
                    for (String av : COMMON_ANTIVIRUS_PROCESSES) {
                        if (key.toLowerCase().contains(av.toLowerCase().replace(".exe", ""))) {
                            result.add(new AntivirusInfo(key, "未知", "运行中"));
                            break;
                        }
                    }
                }
            }

            process.waitFor();
            reader.close();

        } catch (Exception e) {
            logger.error("Error querying registry", e);
        }

        return result;
    }

    private static List<AntivirusInfo> detectViaProcess() {
        List<AntivirusInfo> result = new ArrayList<>();

        try {
            ProcessBuilder pb = new ProcessBuilder("tasklist", "/fo", "csv", "/nh");
            pb.redirectErrorStream(true);
            Process process = pb.start();

            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), "GBK"));
            String line;

            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;

                String[] parts = line.split(",");
                if (parts.length >= 1) {
                    String processName = parts[0].replace("\"", "").trim();

                    for (String av : COMMON_ANTIVIRUS_PROCESSES) {
                        if (processName.equalsIgnoreCase(av)) {
                            String name = av.replace(".exe", "");
                            if (!result.stream().anyMatch(info -> info.name.equalsIgnoreCase(name))) {
                                result.add(new AntivirusInfo(name, "未知", "运行中"));
                            }
                            break;
                        }
                    }
                }
            }

            process.waitFor();
            reader.close();

        } catch (Exception e) {
            logger.error("Error listing processes", e);
        }

        return result;
    }

    private static List<AntivirusInfo> detectAntivirusUnix() {
        List<AntivirusInfo> result = new ArrayList<>();

        try {
            ProcessBuilder pb = new ProcessBuilder("ps", "aux");
            pb.redirectErrorStream(true);
            Process process = pb.start();

            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            String line;
            Pattern pattern = Pattern.compile("(clamav|sophos|avast|avg|kaspersky|norton|mcafee|bitdefender)", Pattern.CASE_INSENSITIVE);

            while ((line = reader.readLine()) != null) {
                Matcher matcher = pattern.matcher(line);
                if (matcher.find()) {
                    String name = matcher.group(1);
                    if (!result.stream().anyMatch(info -> info.name.equalsIgnoreCase(name))) {
                        result.add(new AntivirusInfo(name, "未知", "运行中"));
                    }
                }
            }

            process.waitFor();
            reader.close();

        } catch (Exception e) {
            logger.error("Error detecting antivirus on Unix", e);
        }

        return result;
    }

    public static boolean isAntivirusInstalled() {
        return !detectAntivirus().isEmpty();
    }

    public static String getAntivirusStatus() {
        List<AntivirusInfo> antivirusList = detectAntivirus();

        if (antivirusList.isEmpty()) {
            return "未检测到杀毒软件";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("检测到 ").append(antivirusList.size()).append(" 个杀毒软件:\n");
        for (AntivirusInfo info : antivirusList) {
            sb.append("  - ").append(info.toString()).append("\n");
        }

        return sb.toString();
    }

    public static boolean isProcessRunning(String processName) {
        if (processName == null || processName.trim().isEmpty()) {
            return false;
        }

        String osName = System.getProperty("os.name").toLowerCase();
        if (osName.contains("win")) {
            return isProcessRunningWindows(processName);
        } else if (osName.contains("linux") || osName.contains("mac")) {
            return isProcessRunningUnix(processName);
        }

        return false;
    }

    private static boolean isProcessRunningWindows(String processName) {
        try {
            ProcessBuilder pb = new ProcessBuilder("tasklist", "/fo", "csv", "/nh");
            pb.redirectErrorStream(true);
            Process process = pb.start();

            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), "GBK"));
            String line;

            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;

                String[] parts = line.split(",");
                if (parts.length >= 1) {
                    String runningProcess = parts[0].replace("\"", "").trim();
                    if (runningProcess.equalsIgnoreCase(processName)) {
                        process.waitFor();
                        reader.close();
                        return true;
                    }
                }
            }

            process.waitFor();
            reader.close();

        } catch (Exception e) {
            logger.error("Error checking process on Windows", e);
        }

        return false;
    }

    private static boolean isProcessRunningUnix(String processName) {
        try {
            ProcessBuilder pb = new ProcessBuilder("pgrep", "-x", processName);
            pb.redirectErrorStream(true);
            Process process = pb.start();

            int exitCode = process.waitFor();
            return exitCode == 0;

        } catch (Exception e) {
            logger.error("Error checking process on Unix", e);
        }

        return false;
    }
}
