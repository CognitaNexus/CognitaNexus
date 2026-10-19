package com.cognitanexus.core.catalog.application;

import com.cognitanexus.core.platform.util.Fingerprints;
import com.cognitanexus.core.platform.util.Identifiers;
import com.cognitanexus.sharedkernel.BusinessException;
import com.cognitanexus.sharedkernel.ErrorCode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 学者头像的受控卷存储。
 *
 * <p>与认领材料共用同一套纪律，但有一条关键差别：**头像是公开资源**。认领材料必须经鉴权代理下载，
 * 头像则要能被任何访客读取，因此文件名里的随机标识不是访问控制手段，真正的保护是「只允许图片、
 * 只允许固定尺寸上限、只返回图片类型」。
 *
 * <ol>
 *   <li>类型只看文件头魔数，不信任客户端声明的 Content-Type；
 *   <li>文件名由服务端生成（{@code sav_<随机>.<ext>}），绝不使用上传文件名拼路径；
 *   <li>只接受 PNG / JPEG，单文件不超过 2 MiB——头像是列表页元素，大图只会拖慢档案页；
 *   <li>替换头像时删除旧文件，避免卷里堆无人引用的孤儿文件。
 * </ol>
 */
@Component
public class ScholarAvatarStorage {

  private static final Logger log = LoggerFactory.getLogger(ScholarAvatarStorage.class);

  private static final Set<String> ALLOWED_TYPES = Set.of("image/png", "image/jpeg");

  /** 2 MiB。上限写在存储层而不是控制器：换任何一个入口上传都不能绕过它。 */
  public static final long MAX_SIZE_BYTES = 2L * 1024 * 1024;

  /** 只允许「服务端生成的引用」形态，杜绝 {@code ../../} 之类的构造。 */
  private static final Pattern REF_PATTERN = Pattern.compile("^sav_[A-Za-z0-9]+\\.(png|jpg)$");

  private final Path root;

  public ScholarAvatarStorage(@Value("${cn.storage.scholar-avatar-dir}") String directory) {
    this.root = Path.of(directory).toAbsolutePath().normalize();
  }

  /** 一张已落盘的头像。 */
  public record StoredAvatar(String ref, String mediaType, long size, String sha256) {}

  public StoredAvatar store(String subjectId, String declaredContentType, byte[] content) {
    if (content == null || content.length == 0) {
      throw BusinessException.invalidArgument("头像内容为空");
    }
    if (content.length > MAX_SIZE_BYTES) {
      throw new BusinessException(
          ErrorCode.PAYLOAD_TOO_LARGE, "头像不得超过 " + (MAX_SIZE_BYTES / 1024 / 1024) + " MiB");
    }
    String detected = detectContentType(content);
    if (detected == null) {
      throw BusinessException.invalidArgument("头像仅支持 PNG 或 JPEG");
    }
    if (declaredContentType != null
        && !declaredContentType.isBlank()
        && !ALLOWED_TYPES.contains(declaredContentType.toLowerCase(Locale.ROOT))) {
      throw BusinessException.invalidArgument("不支持的图片类型：" + declaredContentType);
    }

    String ref = Identifiers.next("sav") + ("image/png".equals(detected) ? ".png" : ".jpg");
    Path temp = root.resolve(ref + ".part");
    Path target = root.resolve(ref);
    try {
      Files.createDirectories(root);
      Files.write(temp, content, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
      Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE);
    } catch (IOException ex) {
      try {
        Files.deleteIfExists(temp);
      } catch (IOException cleanupFailure) {
        log.warn("头像临时文件清理失败 file={}", temp.getFileName());
      }
      log.error("头像写入失败 subjectId={}", subjectId, ex);
      throw new BusinessException(ErrorCode.DEPENDENCY_UNAVAILABLE, "头像写入失败，请稍后重试");
    }
    return new StoredAvatar(ref, detected, content.length, Fingerprints.sha256Hex(content));
  }

  /** 读取头像字节；引用不合法与文件缺失都按「不存在」处理，不向外泄露卷结构。 */
  public byte[] read(String ref) {
    if (ref == null || !REF_PATTERN.matcher(ref).matches()) {
      throw BusinessException.notFound("头像不存在");
    }
    Path path = root.resolve(ref).normalize();
    if (!path.startsWith(root)) {
      throw BusinessException.notFound("头像不存在");
    }
    try {
      return Files.readAllBytes(path);
    } catch (IOException ex) {
      throw BusinessException.notFound("头像不存在");
    }
  }

  /** 删除旧头像。失败只记日志：换头像成功后因为删不掉旧文件而整体回滚，对用户毫无意义。 */
  public void deleteQuietly(String ref) {
    if (ref == null || !REF_PATTERN.matcher(ref).matches()) {
      return;
    }
    try {
      Files.deleteIfExists(root.resolve(ref));
    } catch (IOException ex) {
      log.warn("旧头像删除失败 ref={}", ref);
    }
  }

  /** 只依据文件头判断类型。 */
  static String detectContentType(byte[] content) {
    if (content.length >= 8
        && (content[0] & 0xFF) == 0x89
        && content[1] == 0x50
        && content[2] == 0x4E
        && content[3] == 0x47) {
      return "image/png";
    }
    if (content.length >= 3
        && (content[0] & 0xFF) == 0xFF
        && (content[1] & 0xFF) == 0xD8
        && (content[2] & 0xFF) == 0xFF) {
      return "image/jpeg";
    }
    return null;
  }
}
