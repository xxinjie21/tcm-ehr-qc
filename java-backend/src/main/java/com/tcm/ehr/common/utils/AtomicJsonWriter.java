package com.tcm.ehr.common.utils;

import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * 原子写 JSON 配置文件：先写同目录临时文件，再 move 覆盖目标。
 *
 * 为什么不能直接 {@code writeValue(file, obj)}：写到一半进程被杀、磁盘满或权限变化，
 * 目标文件就成了半截 JSON —— 下次启动读它直接解析失败，配置「无声回退默认」，
 * 而且原内容也已经被截断，回不去了。
 *
 * 关键点：临时文件必须与目标**同目录**。跨文件系统的 move 不是原子的，
 * ATOMIC_MOVE 会直接报错；同目录下才是「要么旧内容、要么新内容」。
 *
 * 调用方负责兜异常并决定降级口径（本项目两个 store 都是「内存已生效，落盘失败只告警」）。
 */
public final class AtomicJsonWriter {

    private AtomicJsonWriter() {
    }

    /**
     * 原子写入。
     *
     * @param mapper   JSON 序列化器（用 withDefaultPrettyPrinter，便于管理员手工核对）
     * @param target   目标文件
     * @param value    要写入的对象
     * @throws IOException 目录创建、写临时文件或 move 失败
     */
    public static void write(ObjectMapper mapper, Path target, Object value) throws IOException {
        Path dir = target.toAbsolutePath().getParent();
        if (dir != null) {
            Files.createDirectories(dir);
        }
        // 临时文件与目标同目录；前缀带目标名，出问题时一眼看得出是谁的半成品
        Path tmp = Files.createTempFile(dir, target.getFileName().toString() + ".", ".tmp");
        try {
            mapper.writerWithDefaultPrettyPrinter().writeValue(tmp.toFile(), value);
            try {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                // 某些文件系统（如部分网络盘）不支持原子 move：降级为普通替换，
                // 至少保证「不会留下半截文件」，只是替换那一瞬不是原子的
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            // 失败要把临时文件清掉，别在配置目录里堆 .tmp
            try {
                Files.deleteIfExists(tmp);
            } catch (IOException ignored) {
                // 清理失败不该盖住真正的错误
            }
            throw e;
        }
    }
}
