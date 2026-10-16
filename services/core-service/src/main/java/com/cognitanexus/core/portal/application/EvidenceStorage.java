package com.cognitanexus.core.portal.application;

import com.cognitanexus.core.platform.util.Fingerprints;
import com.cognitanexus.core.platform.util.Identifiers;
import com.cognitanexus.core.portal.domain.ClaimEvidence;
import com.cognitanexus.sharedkernel.BusinessException;
import com.cognitanexus.sharedkernel.ErrorCode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Locale;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 认领材料的受控卷存储。
 *
 * <p>依据：架构 V0.2 §10.4。规则：
 *
 * <ol>
 *   <li>先写临时文件，校验类型、大小与魔数，再登记引用；失败时清理临时文件；
 *   <li>文件名使用随机内部标识，绝不使用上传文件名拼路径（避免路径穿越与编码问题）；
 *   <li>只允许 PDF / PNG / JPEG，拒绝压缩包与可执行内容；
 *   <li>文件不经静态目录暴露，下载必须经过核心服务鉴权代理。
 * </ol>
 *
 * <p>目录本身不是安全边界，因此这里不声称已具备完整的内容安全扫描能力；真实材料接入前
 * 仍需按 OQ03 确认审核标准与清理规则。
 */
@Component
public class EvidenceStorage {

  private static final Logger log = LoggerFactory.getLogger(EvidenceStorage.class);

  private static final Set<String> ALLOWED_TYPES =
      Set.of("application/pdf", "image/png", "image/jpeg");

  private static final long MAX_SIZE = ClaimEvidence.MAX_SIZE_BYTES;

  private final Path root;

  public EvidenceStorage(@Value("${cn.storage.claim-evidence-dir}") String directory) {
    this.root = Path.of(directory).toAbsolutePath().normalize();
  }

  /**
   * 保存材料并返回其元数据。
   *
   * @param declaredContentType 客户端声明的类型，不作为信任依据，须与魔数一致
   */
  public ClaimEvidence store(
      String claimRequestId, String originalFilename, String declaredContentType, byte[] content) {
    if (content == null || content.length == 0) {
      throw BusinessException.invalidArgument("材料内容为空");
    }
    if (content.length > MAX_SIZE) {
      throw new BusinessException(
          ErrorCode.PAYLOAD_TOO_LARGE, "单个材料不得超过 " + (MAX_SIZE / 1024 / 1024) + " MiB");
    }
    String detected = detectContentType(content);
    if (detected == null) {
      throw BusinessException.invalidArgument("仅支持 PDF、PNG、JPEG 格式的材料");
    }
    if (declaredContentType != null
        && !declaredContentType.isBlank()
        && !ALLOWED_TYPES.contains(declaredContentType.toLowerCase(Locale.ROOT))) {
      throw BusinessException.invalidArgument("不支持的材料类型：" + declaredContentType);
    }

    String fileRef = Identifiers.next("evd") + extensionOf(detected);
    try {
      Files.createDirectories(root);
    } catch (IOException ex) {
      throw new BusinessException(ErrorCode.DEPENDENCY_UNAVAILABLE, "材料存储目录不可用");
    }

    Path temp = root.resolve(fileRef + ".part");
    Path target = root.resolve(fileRef);
    try {
      Files.write(temp, content, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
      Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE);
    } catch (IOException ex) {
      try {
        Files.deleteIfExists(temp);
      } catch (IOException cleanupFailure) {
        log.warn("临时材料清理失败 file={}", temp.getFileName());
      }
      log.error("材料写入失败 claimRequestId={}", claimRequestId, ex);
      throw new BusinessException(ErrorCode.DEPENDENCY_UNAVAILABLE, "材料写入失败，请稍后重试");
    }

    return new ClaimEvidence(
        Identifiers.next("evm"),
        claimRequestId,
        fileRef,
        Fingerprints.sha256Hex(content),
        detected,
        content.length,
        java.time.Instant.now());
  }

  /** 读取材料字节；供核心服务在鉴权后代理下载。 */
  public byte[] read(String fileRef) {
    if (fileRef == null
        || fileRef.contains("/")
        || fileRef.contains("\\")
        || fileRef.contains("..")) {
      throw BusinessException.invalidArgument("材料引用不合法");
    }
    Path path = root.resolve(fileRef).normalize();
    if (!path.startsWith(root)) {
      throw BusinessException.accessDenied("材料引用越界");
    }
    try {
      return Files.readAllBytes(path);
    } catch (IOException ex) {
      throw BusinessException.notFound("材料不存在或已被清理");
    }
  }

  /** 只依据文件头判断类型，不信任客户端声明的 Content-Type。 */
  private String detectContentType(byte[] content) {
    if (content.length >= 4
        && content[0] == 0x25
        && content[1] == 0x50
        && content[2] == 0x44
        && content[3] == 0x46) {
      return "application/pdf";
    }
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

  private String extensionOf(String contentType) {
    return switch (contentType) {
      case "application/pdf" -> ".pdf";
      case "image/png" -> ".png";
      default -> ".jpg";
    };
  }
}
