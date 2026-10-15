package com.doumiot.config;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.core.env.Environment;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.net.URI;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.*;

/** MSI 启动器的用户数据目录、单实例锁、托盘及浏览器入口。 */
public final class DesktopSupport {
    private static DesktopInstance instance;
    private DesktopSupport() { }

    public static void prepare() throws Exception {
        if (!Boolean.getBoolean("doumi.desktop")) return;
        if (System.getenv("DOUMI_DATA_DIR") == null && System.getProperty("doumi.storage.data-dir") == null) {
            String local = System.getenv("LOCALAPPDATA");
            Path base = local == null ? Paths.get(System.getProperty("user.home"), ".doumi") : Paths.get(local, "DoumiShell");
            System.setProperty("doumi.storage.data-dir", base.resolve("data").toString());
        }
        String data = System.getProperty("doumi.storage.data-dir", System.getenv("DOUMI_DATA_DIR"));
        Path directory = Paths.get(data).toAbsolutePath();
        Files.createDirectories(directory);
        instance = new DesktopInstance(directory);
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(45);
        while (!instance.tryAcquire()) {
            String existing = instance.runningUrl();
            if (existing != null) {
                instance.close();
                if (!"false".equals(System.getProperty("doumi.desktop.open-browser"))) browse(existing);
                System.exit(0);
            }
            if (System.nanoTime() > deadline) {
                instance.close();
                throw new IllegalStateException("Doumi Shell 已在启动或关闭中，请稍后重试");
            }
            Thread.sleep(150);
        }
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try { instance.close(); } catch (java.io.IOException ignored) { }
        }, "doumi-instance-cleanup"));
        System.setProperty("java.awt.headless", "false");
        if (System.getProperty("logging.file.name") == null)
            System.setProperty("logging.file.name", directory.resolve("logs/doumi.log").toString());
    }

    public static ApplicationListener<ApplicationReadyEvent> listener() {
        return event -> {
            if (!Boolean.getBoolean("doumi.desktop")) return;
            Environment env = event.getApplicationContext().getEnvironment();
            String url = "http://localhost:" + env.getProperty("local.server.port", env.getProperty("server.port", "80")) + "/";
            try { instance.publish(url); }
            catch (java.io.IOException e) { throw new IllegalStateException("无法保存 Doumi Shell 运行地址", e); }
            EventQueue.invokeLater(() -> {
                if (SystemTray.isSupported()) {
                    BufferedImage icon = new BufferedImage(32, 32, BufferedImage.TYPE_INT_ARGB);
                    Graphics2D graphics = icon.createGraphics();
                    graphics.setColor(new Color(42, 102, 210)); graphics.fillRoundRect(0, 0, 32, 32, 8, 8);
                    graphics.setColor(Color.WHITE); graphics.setFont(new Font("SansSerif", Font.BOLD, 22));
                    graphics.drawString(">", 6, 24); graphics.dispose();
                    PopupMenu menu = new PopupMenu();
                    MenuItem open = new MenuItem("Open Doumi Shell"); open.addActionListener(action -> browse(url)); menu.add(open);
                    MenuItem quit = new MenuItem("Exit Doumi Shell");
                    quit.addActionListener(action -> {
                        event.getApplicationContext().close();
                        System.exit(0);
                    });
                    menu.add(quit);
                    TrayIcon tray = new TrayIcon(icon, "Doumi Shell - " + url, menu); tray.setImageAutoSize(true);
                    tray.addActionListener(action -> browse(url));
                    try { SystemTray.getSystemTray().add(tray); }
                    catch (AWTException ignored) { /* 浏览器入口仍然可用 */ }
                }
                if (!"false".equals(System.getProperty("doumi.desktop.open-browser"))) browse(url);
            });
        };
    }

    private static void browse(String url) {
        try { if (Desktop.isDesktopSupported()) Desktop.getDesktop().browse(URI.create(ApplicationVersion.browserUrl(url))); }
        catch (Exception ignored) { /* 用户可直接访问 localhost */ }
    }
}
