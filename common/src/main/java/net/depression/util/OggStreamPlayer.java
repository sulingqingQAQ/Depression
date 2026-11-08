package net.depression.util;

import net.depression.Depression;
import net.depression.screen.rhythmcraft.SongProgressSlider;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.client.sounds.SoundEngine;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundSource;

import javax.sound.sampled.*;
import java.io.File;
import java.io.IOException;
import java.util.Stack;

public class OggStreamPlayer {
    public SongProgressSlider slider;
    private SourceDataLine sourceLine;
    private AudioInputStream audioInputStream;
    private AudioFormat decodedFormat; // 解码后的格式
    private AudioFormat fileFormat; // 文件格式
    //播放线程与主线程（stop/pause/resume）共享这两个标志，必须 volatile 才能保证 stop() 的写入被播放线程及时看到
    public volatile boolean isPlaying = false;
    public volatile boolean isPaused = false;
    public boolean isEscPaused = false;
    public boolean isSpacePaused = false;
    private Thread playThread;
    private static final long STOP_JOIN_TIMEOUT_MILLIS = 500L; //stop() 等待播放线程退出的上限
    private long totalBytesRead = 0;
    private long bytesToSkip = 0;
    private double soughtTime = 0;
    private String filePath;
    private FloatControl volumeControl;
    private float initialVolume;
    private float midVolume;
    private float minVolume;
    private float maxVolume;
    private double durationInSeconds;
    private long bytesToIgnore;
    //快进任务的状态位跨线程读写（UI 线程发起、工作线程复位），必须 volatile 才能被及时看到
    public volatile boolean isForwarding;
    private volatile Thread forwardThread;
    //快进任务身份：每次 forward/cancel 自增，旧任务醒来时据此判断自己是否已被取代
    private volatile long forwardTaskId = 0L;
    public void load(String filePath, double durationInSeconds) throws UnsupportedAudioFileException, IOException, LineUnavailableException {
        this.filePath = filePath; // 保存文件路径
        // 获取音频输入流
        AudioInputStream in = AudioSystem.getAudioInputStream(new File(filePath));
        AudioFormat baseFormat = in.getFormat();
        fileFormat = AudioSystem.getAudioFileFormat(new File(filePath)).getFormat();
        this.durationInSeconds = durationInSeconds;
        // 创建解码后的音频格式
        decodedFormat = new AudioFormat(
                AudioFormat.Encoding.PCM_SIGNED,
                baseFormat.getSampleRate(),
                16, // 16位
                baseFormat.getChannels(),
                baseFormat.getChannels() * 2, // 每帧的字节数 (16位 = 2字节)
                baseFormat.getSampleRate(),
                false // 大端(false)或小端(true)，通常为false（小端）
        );

        // 转换为解码后的音频流
        audioInputStream = AudioSystem.getAudioInputStream(decodedFormat, in);

        // 获取数据行信息
        DataLine.Info info = new DataLine.Info(SourceDataLine.class, decodedFormat);
        // 获取数据行
        sourceLine = (SourceDataLine) AudioSystem.getLine(info);
        sourceLine.open(decodedFormat);

        if (sourceLine.isControlSupported(FloatControl.Type.MASTER_GAIN)) {
            volumeControl = (FloatControl) sourceLine.getControl(FloatControl.Type.MASTER_GAIN);
            Options options = Minecraft.getInstance().options;
            initialVolume = options.getSoundSourceVolume(SoundSource.MUSIC);
            midVolume = volumeControl.getValue();
            float gap = Math.min(midVolume - volumeControl.getMinimum(), volumeControl.getMaximum() - midVolume);
            minVolume = midVolume - gap;
            maxVolume = midVolume + gap;
        }
        else {
            Depression.LOGGER.error("Volume control not supported");
        }
    }

    public void play() {
        cancelForward(); //上一次播放遗留的快进任务不得影响这次播放
        isPlaying = true;
        isPaused = false;
        sourceLine.start();

        playThread = new Thread(() -> {
            byte[] buffer = new byte[4096];
            int bytesRead = 0;
            boolean reachedEof = false; //只有读到文件末尾（自然结束）才需要把缓冲里剩下的音频放完
            totalBytesRead = 0;
            try {
                while (isPlaying && (bytesRead = audioInputStream.read(buffer, 0, buffer.length)) != -1) {
                    while (isPaused) {
                        Thread.sleep(100);
                    }
                    if (!isPlaying) { //暂停期间被主动停止：不再往已被 flush 的行里写
                        break;
                    }
                    bytesRead -= bytesRead % decodedFormat.getFrameSize();
                    sourceLine.write(buffer, 0, bytesRead);
                    totalBytesRead += bytesRead;
                }
                reachedEof = isPlaying && bytesRead == -1;
            } catch (Exception e) {
                e.printStackTrace();
            }
            finally {
                try {
                    if (reachedEof && isPlaying) {
                        //自然结束：把缓冲里的尾巴放完。主动停止（isPlaying 已被 stop() 置 false）不 drain：
                        //对已暂停（stop）的行，drain 会一直等到行恢复运行，导致主线程 join 永远不返回
                        sourceLine.drain();
                    }
                    sourceLine.stop();
                    sourceLine.close();
                } catch (Exception e) {
                    e.printStackTrace();
                }
                try {
                    audioInputStream.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
                isPlaying = false;
                isPaused = false;
            }
        });

        playThread.start();
    }

    public void pause() {
        isPaused = true;
        sourceLine.stop();
    }

    public void resume() {
        if (isEscPaused || isSpacePaused) {
            return;
        }
        isPaused = false;
        sourceLine.start();
    }

    public void stop() {
        cancelForward(); //快进任务必须随播放一起停，否则它醒来会恢复暂停态/在已关闭的行上操作
        isPlaying = false;
        isPaused = false;
        Thread thread = playThread;
        if (thread != null && thread.isAlive()) {
            //先让行停下并丢弃缓冲，唤醒可能阻塞在 write 里的播放线程；主动停止不需要把缓冲放完
            try {
                sourceLine.stop();
                sourceLine.flush();
            } catch (Exception e) {
                e.printStackTrace();
            }
            try {
                //这里在客户端主线程调用，join 必须有超时，不能永远等下去
                thread.join(STOP_JOIN_TIMEOUT_MILLIS);
                if (thread.isAlive()) {
                    //还没退出：关闭行（会让阻塞中的 write/drain 立即返回）并打断 sleep，再等一小会儿
                    try {
                        sourceLine.close();
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                    thread.interrupt();
                    thread.join(STOP_JOIN_TIMEOUT_MILLIS);
                    if (thread.isAlive()) {
                        Depression.LOGGER.warn("Ogg play thread did not exit in time, giving up waiting");
                    }
                }
            } catch (InterruptedException e) {
                e.printStackTrace();
                Thread.currentThread().interrupt();
            }
        }
    }
    public void seek(long ticks) {
        cancelForward(); //跳转会让行重新 open/start，旧快进任务不能再对它恢复暂停态
        boolean wasPaused = isPaused;
        pause();
        sourceLine.stop();
        sourceLine.flush();
        sourceLine.close();
        double seconds = ticks / 20d;
        // 计算应跳过的字节数
        int frameSize = decodedFormat.getFrameSize();
        long bytesPerSecond = (long) (frameSize * decodedFormat.getFrameRate());
        long totalBytesToSkip = (long) (seconds * bytesPerSecond);
        totalBytesToSkip -= totalBytesToSkip % frameSize;

        bytesToIgnore = (long) (Math.min(1d, seconds) * bytesPerSecond);
        bytesToSkip = totalBytesToSkip - bytesToIgnore;
        try {
            audioInputStream.close();
            // 重新加载音频文件
            AudioInputStream in = AudioSystem.getAudioInputStream(new File(filePath));
            // 转换为解码后的音频流
            audioInputStream = AudioSystem.getAudioInputStream(decodedFormat, in);
            totalBytesRead = 0;
            while (totalBytesRead < bytesToSkip) {
                long skipped = audioInputStream.skip(bytesToSkip - totalBytesRead);
                if (skipped <= 0) {
                    break;
                }
                totalBytesRead += skipped;
            }

            byte[] ignoreBuffer = new byte[4096];
            long ignored;
            while (totalBytesRead < totalBytesToSkip) {
                try {
                    int length;
                    if (totalBytesToSkip - totalBytesRead < ignoreBuffer.length) {
                        length = (int) (totalBytesToSkip - totalBytesRead);
                    }
                    else {
                        length = ignoreBuffer.length;
                    }
                    ignored = audioInputStream.read(ignoreBuffer, 0, length - length % frameSize);
                    if (length % frameSize > 0 && 0 < ignored && ignored < ignoreBuffer.length) { // 读取不足一帧则不读取，避免死循环
                        totalBytesRead += ignored;
                        break;
                    }
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
                if (ignored < 0) {
                    break;
                }
                totalBytesRead += ignored;
            }
            sourceLine.open(decodedFormat);
            soughtTime = totalBytesRead / (decodedFormat.getFrameRate() * decodedFormat.getFrameSize());
        } catch (UnsupportedAudioFileException | IOException | LineUnavailableException e) {
            throw new RuntimeException(e);
        }
        isPaused = wasPaused;
        if (!isPaused) {
            sourceLine.start();
        }
    }
    public void forward(long ticks){
        cancelForward(); //同一时刻只允许一个快进任务：旧的先作废，避免它在新任务之后恢复暂停/复位标志
        boolean wasPausedBeforeForward = isPaused;
        isPaused = false;
        sourceLine.start();
        long taskId = ++forwardTaskId;
        isForwarding = true;
        Thread thread = new Thread(() -> {
            boolean sleptThrough = false;
            try {
                Thread.sleep(ticks * 50);
                sleptThrough = true;
            } catch (InterruptedException e) {
                //被 stop()/seek()/play()/新的 forward() 取消：不恢复暂停态，状态由取消方负责
                Thread.currentThread().interrupt();
            } finally {
                //身份校验：只有仍是当前任务才允许复位；被取代的旧任务醒来后不得影响新任务/新歌曲
                if (forwardTaskId == taskId) {
                    if (sleptThrough && wasPausedBeforeForward) {
                        restorePausedStateAfterForward();
                    }
                    isForwarding = false;
                    forwardThread = null;
                }
            }
        }, "depression-ogg-forward");
        thread.setDaemon(true); //快进只是界面节流，不能拖住 JVM 退出
        forwardThread = thread;
        thread.start();
    }

    /** 作废未完成的快进任务：自增身份使旧任务的 finally 校验失败，并打断它可能仍在进行的 sleep。 */
    private void cancelForward() {
        ++forwardTaskId;
        isForwarding = false;
        Thread thread = forwardThread;
        forwardThread = null;
        if (thread != null && thread.isAlive()) {
            thread.interrupt();
        }
    }

    /** 快进结束恢复"快进前的暂停态"；播放已停止或行已关闭时跳过，避免与 stop() 的 close 竞争。 */
    private void restorePausedStateAfterForward() {
        if (!isPlaying) {
            return;
        }
        try {
            pause();
        } catch (IllegalStateException e) {
            Depression.LOGGER.debug("Skip restoring pause state after forward: audio line is already closed");
        }
    }
    public double getElapsedTimeInSeconds() {
        return soughtTime + sourceLine.getMicrosecondPosition() / 1000000d;
    }

    public void setVolume(float value) {
        if (volumeControl != null) {
            value = Math.max(volumeControl.getMinimum(), Math.min(volumeControl.getMaximum(), value));
            volumeControl.setValue(value);
        }
    }
    public void setVolumeLinear(float volume) {
        if (volumeControl != null) {
            float dB;
            if (volume == 0) {
                dB = volumeControl.getMinimum();
            }
            else if (volume < 0.5f) {
                dB = (midVolume - minVolume) * volume + minVolume;
            }
            else {
                dB = (maxVolume - midVolume) * (volume - 0.5f) + midVolume;
            }
            volumeControl.setValue(dB);
        }
    }

    public double getDurationInSeconds() {
        return durationInSeconds;
    }
}
