package com.tcm.ehr.service.impl;

import tools.jackson.databind.ObjectMapper;
import com.tcm.ehr.common.utils.RecordUtil;
import com.tcm.ehr.domain.po.TermEntry;
import com.tcm.ehr.service.IDictionaryFileService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 词典文件服务实现：data/dictionaries/*.json 读写、备份、回滚
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DictionaryFileServiceImpl implements IDictionaryFileService {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");

    private final ObjectMapper mapper;

    @Value("${dictionary.dir:data/dictionaries}")
    private String dictDir;

    /** currentVersion 缓存：内容戳不变则复用，避免批任务逐条重算 */
    private volatile String cachedVersion;
    private volatile long cachedStamp = -1;

    @Override
    /**
     * 词典数据目录（配置项 {@code dictionary.dir}，默认 {@code data/dictionaries}）。
     *
     * @return 词典根目录路径，不校验其是否存在
     */
    public Path dir() {
        return Paths.get(dictDir);
    }

    @Override
    /**
     * 备份目录，即词典目录下的 {@code backup} 子目录；不校验其是否存在。
     *
     * @return 备份目录路径
     */
    public Path backupDir() {
        return dir().resolve("backup");
    }

    @Override
    /**
     * 词典类型到 JSON 文件名的映射，文件名由 EntityTypes 统一登记。
     *
     * @param type 词典类型（disease / pattern / symptom / herb / formula）
     * @return 对应的 JSON 文件名
     * @throws IllegalArgumentException 类型未登记时抛出
     */
    public String fileNameOf(String type) {
        // 1. 文件名只认 EntityTypes 登记的那 5 类，未登记直接拒绝
        String name = com.tcm.ehr.common.config.EntityTypes.fileNameOf(type);
        if (name == null) {
            throw new IllegalArgumentException("未知词典类型: " + type);
        }
        return name;
    }

    @Override
    /**
     * 读取某类词典的全部词条。
     *
     * <p>文件不存在视为「该类词典为空」并返回空列表，而非报错 —— 首次导入时目录尚无文件。</p>
     *
     * @param type 词典类型
     * @return 词条列表；文件不存在时为空列表
     * @throws IOException 文件存在但读取或 JSON 解析失败
     */
    public List<TermEntry> read(String type) throws IOException {
        Path file = dir().resolve(fileNameOf(type));
        // 1. 文件不存在按「该类词典为空」处理，不报错（首次导入时目录尚无文件）
        if (!Files.exists(file)) {
            return new ArrayList<>();
        }
        // 2. 存在但内容坏了要报错：那属于数据损坏，静默当空会让人以为词典被清空了
        return mapper.readValue(file.toFile(),
                mapper.getTypeFactory().constructCollectionType(List.class, TermEntry.class));
    }

    @Override
    /**
     * 全量覆盖写入某类词典文件（JSON 美化输出），目录不存在时自动创建。
     *
     * <p>属覆盖式落盘，调用方需自行保证已先备份。</p>
     *
     * @param type 词典类型
     * @param entries 全量词条
     * @throws IOException 目录创建或写文件失败
     */
    public void write(String type, List<TermEntry> entries) throws IOException {
        // 1. 先建目录（首次导入时目录还不存在）
        Files.createDirectories(dir());
        // 2. 美化输出整文件覆盖：JSON 要能被人读懂并手工核对
        Path file = dir().resolve(fileNameOf(type));
        mapper.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), entries);
    }

    @Override
    /**
     * 备份当前词典文件到备份目录，备份名形如 {@code <文件名>.bak_yyyyMMdd_HHmmss}。
     *
     * @param type 词典类型
     * @return 备份文件名；当前词典文件不存在（首次导入）时返回 {@code null}
     * @throws IOException 备份目录创建或文件复制失败
     */
    public String backup(String type) throws IOException {
        Path file = dir().resolve(fileNameOf(type));
        // 1. 没有原文件就没得备份，返回 null 让调用方知道这次没有可回滚点
        if (!Files.exists(file)) {
            return null;
        }
        // 2. 备份名带时间戳，同一秒内两次备份才会撞名（此时覆盖旧的）
        Files.createDirectories(backupDir());
        String backupName = fileNameOf(type) + ".bak_" + LocalDateTime.now().format(TS);
        Files.copy(file, backupDir().resolve(backupName), StandardCopyOption.REPLACE_EXISTING);
        return backupName;
    }

    @Override
    /**
     * 用备份文件覆盖当前词典文件，完成回滚。
     *
     * <p>备份文件名必须以该类型的 {@code <文件名>.bak_} 前缀开头，用以拦截路径穿越串，
     * 防止借备份名指向备份目录之外的任意文件。</p>
     *
     * @param type 词典类型
     * @param backupFilename 备份文件名（非全路径）
     * @throws IOException 词典目录创建或文件复制失败
     * @throws IllegalArgumentException 备份文件不存在，或文件名与该词典类型不匹配
     */
    public void restore(String type, String backupFilename) throws IOException {
        Path src = backupDir().resolve(backupFilename);
        // 1. 备份不存在直接报错
        if (!Files.exists(src)) {
            throw new IllegalArgumentException("备份文件不存在: " + backupFilename);
        }
        // 2. 前缀不符即拒绝：这是防路径穿越的关键一步，必须在 copy 之前
        if (!backupFilename.startsWith(fileNameOf(type) + ".bak_")) {
            throw new IllegalArgumentException("备份文件与词典类型不匹配");
        }
        // 3. 校验通过才覆盖当前词典
        Files.createDirectories(dir());
        Files.copy(src, dir().resolve(fileNameOf(type)), StandardCopyOption.REPLACE_EXISTING);
    }

    @Override
    /**
     * 列出该类词典的全部备份，按时间倒序。
     *
     * <p>每项含 filename（文件名）、time（可读时间）、count（该备份词条数）、
     * delta（相对当前词典的增减，形如 +3 / -2 / 0）。单个备份文件解析失败时词条数按 0 计，
     * 不因坏文件拖垮整个列表。</p>
     *
     * @param type 词典类型
     * @return 备份条目列表；备份目录不存在时为空列表
     * @throws IOException 遍历备份目录或读取当前词典失败
     */
    public List<Map<String, String>> listBackups(String type) throws IOException {
        List<Map<String, String>> result = new ArrayList<>();
        Path backupDir = backupDir();
        // 1. 备份目录都没有就当没备份过
        if (!Files.exists(backupDir)) {
            return result;
        }
        // 2. 先算当前词条数，delta 要拿它做基准
        int currentCount = read(type).size();
        String prefix = fileNameOf(type) + ".bak_";
        // 3. 只扫本类型前缀的文件（不扫别的类型的备份）
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(backupDir, prefix + "*")) {
            for (Path p : stream) {
                String name = p.getFileName().toString();
                String ts = name.substring(prefix.length());
                LocalDateTime t = LocalDateTime.parse(ts, TS);
                int count = countOf(p);
                int delta = count - currentCount;
                Map<String, String> row = new LinkedHashMap<>();
                row.put("filename", name);
                row.put("time", t.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
                row.put("count", String.valueOf(count));
                // 正数带 + 号：页面上要能一眼看出是多了还是少了
                row.put("delta", (delta > 0 ? "+" : "") + delta);
                result.add(row);
            }
        }
        // 4. 按时间倒序：最近的备份在最上面
        result.sort((a, b) -> b.get("time").compareTo(a.get("time")));
        return result;
    }

    /** 备份文件词条数；解析失败按 0 计（不因单个坏文件拖垮整个列表） */
    private int countOf(Path path) {
        // 1. 数词条数；坏文件给 0，只记警告，不让整个备份列表挂掉
        try {
            List<TermEntry> entries = mapper.readValue(path.toFile(),
                    mapper.getTypeFactory().constructCollectionType(List.class, TermEntry.class));
            return entries.size();
        } catch (Exception e) {
            log.warn("[词典] 备份文件解析失败，词条数按 0 计: {} ({})", path.getFileName(), e.getMessage());
            return 0;
        }
    }

    @Override
    /**
     * 判断备份文件是否存在，同时兼作路径穿越防护。
     *
     * <p>先按与 {@link #restore} 相同的前缀规则过滤：文件名为 null 或前缀不符时直接返回
     * {@code false}，避免用 {@code ../..} 之类的名字探测备份目录之外的路径。</p>
     *
     * @param type 词典类型
     * @param backupFilename 备份文件名
     * @return 前缀合法且文件存在时为 {@code true}
     */
    public boolean backupExists(String type, String backupFilename) {
        // 与 restore() 同一套前缀校验：否则 backupFilename 传 ../.. 之类可以探测任意路径是否存在
        if (backupFilename == null || !backupFilename.startsWith(fileNameOf(type) + ".bak_")) {
            return false;
        }
        return Files.exists(backupDir().resolve(backupFilename));
    }

    /**
     * 词典内容版本（5 个文件内容拼接后的 MD5 前 12 位）。
     *
     * <p>先算「文件指纹」（修改时间 + 大小）判缓存，<b>命中就不再读文件</b>。
     * 原实现把读取与拼接放在缓存判断之前，等于每次调用都全量读 5 个文件 ——
     * 而批量解析是<b>逐条病历</b>调它（`NlpBatchServiceImpl.processOne`），
     * 3.5 万条就是约 17.5 万次文件读取，缓存只省下最后一次的 MD5（审查报告 G4）。</p>
     *
     * <p>指纹用「修改时间 + 大小」：本项目改词典只有两条路径（导入、回滚），
     * 都是整文件覆盖，两者都会变；不存在「内容变了而指纹没变」的情形。</p>
     */
    @Override
    public String currentVersion() {
        try {
            // 1. 先算指纹（修改时间 + 大小），这一步不读文件内容
            long stamp = 0;
            boolean allPresent = true;
            for (String type : com.tcm.ehr.common.config.EntityTypes.dictKeys()) {
                Path f = dir().resolve(fileNameOf(type));
                if (Files.exists(f)) {
                    stamp = stamp * 31 + Files.getLastModifiedTime(f).toMillis() + Files.size(f);
                } else {
                    allPresent = false;
                }
            }
            // 2. 指纹没变且上次算过 → 直接返回缓存，省掉 5 次文件读取
            if (allPresent && cachedVersion != null && stamp == cachedStamp) {
                return cachedVersion;
            }
            // 3. 指纹变了（或首次）才真读 5 个文件拼串算 MD5
            StringBuilder sb = new StringBuilder();
            for (String type : com.tcm.ehr.common.config.EntityTypes.dictKeys()) {
                Path f = dir().resolve(fileNameOf(type));
                sb.append(type).append('=');
                if (Files.exists(f)) {
                    sb.append(Files.readString(f, StandardCharsets.UTF_8));
                }
                sb.append('\n');
            }
            String v = RecordUtil.md5Hex(sb.toString()).substring(0, 12);
            // 4. 连同指纹一起缓存，供下次比对
            cachedVersion = v;
            cachedStamp = stamp;
            return v;
        } catch (Exception e) {
            // 5. 算不出来给固定串：版本只用于判断"要不要重算"，不能因为算不出就报错
            log.warn("[词典] 版本计算失败: {}", e.getMessage());
            return "unknown";
        }
    }

    @Override
    /**
     * 以 UTF-8 读取文本文件的全部内容。
     *
     * @param path 目标文件路径
     * @return 文件文本内容
     * @throws IOException 读取失败
     */
    public String readText(Path path) throws IOException {
        return Files.readString(path, StandardCharsets.UTF_8);
    }
}
