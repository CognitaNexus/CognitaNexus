package com.cognitanexus.core.catalog.application;

/**
 * 成果诚信状态的最小读取端口。
 *
 * <p>为什么放在目录侧定义、而不是让目录直接依赖诚信模块：诚信声明的登记需要外发目录变更事件，
 * 若目录侧反过来注入诚信应用服务，两个 Bean 会互相等待（构造器循环依赖），Spring 启动即失败。
 * 把「只读一个状态码」这个最小需求定义成目录自己的出站端口，依赖方向就变成单向：
 * 诚信模块实现它，目录只看见这个接口。
 *
 * <p>它只回答一个问题——「这条成果当前对外应当显示成什么诚信状态」。理由、声明主体、链接等
 * 内容属于详情页，不进投影载荷：事件只需要驱动列表上的标记，把整段声明塞进载荷会让每次声明都
 * 复制一份可能很长的文本。
 */
public interface AchievementIntegrityPort {

  /** 返回 {@code NONE} / {@code CONCERN} / {@code CORRECTION} / {@code RETRACTION}，不返回 null。 */
  String statusOf(String achievementId);
}
