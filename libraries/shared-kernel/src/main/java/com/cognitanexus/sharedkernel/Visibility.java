package com.cognitanexus.sharedkernel;

/**
 * 公开可见性。
 *
 * <p>依据：BR-IMP-01。只公开许可允许且当前生效的实体与合格关系；待审、争议与敏感证据不公开。
 */
public enum Visibility {
  /** 当前公开。 */
  PUBLIC,
  /** 受控：存在但不对外公开，例如待审版本、争议对象。 */
  RESTRICTED,
  /** 隐藏：已撤销公开资格，例如来源展示许可被撤销。 */
  HIDDEN;

  public boolean publiclyVisible() {
    return this == PUBLIC;
  }
}
