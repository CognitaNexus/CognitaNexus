# CI/CD 恢复说明（2026-10-23）

## 一句话

仓库侧的改动已经做完，密钥已经在生产机上授权好，**只剩 GitHub 网页上两个 secret 等你改**。

---

## 一、现在是什么状态

| 项目 | 状态 |
| --- | --- |
| 构建与测试（verify + 覆盖率门禁 + 前端 build） | 一直是好的，没动 |
| 部署目标 | 指向旧机 150.230.223.11（已被 Oracle 回收） |
| 部署账号 | 写死 ubuntu，新机是 buaa |
| 暂存目录 | 写死 /home/ubuntu/cn-incoming |
| 部署密钥 | CI 用的 cn-deploy 公钥**原先不在新机**，现已追加 |
| 前端能否自动部署 | 不能（/var/www/cn 属主 root，buaa 无 sudo） |
| 数据库自动备份 | **新机没有装** cn-db-backup.timer |

---

## 二、你需要做的事

### 第 1 步（必做）：改两个 secret

打开 https://github.com/CognitaNexus/CognitaNexus/settings/secrets/actions

| Secret | 改成 |
| --- | --- |
| CN_SSH_HOST | 168.110.3.136 |
| CN_SSH_USER | buaa |

另外两个**先别动**：

- CN_SSH_PORT 保持 22，新机就是 22。
- CN_SSH_KEY 保持原值。已核验：secret 里那把私钥的指纹是
  SHA256:7wXsS4W0OzK58kJ+VgRVzmqF/grj/Pq8TLgljOXvVfQ，对应的公钥
  cn-deploy-github-actions 已经写进新机的 authorized_keys，用这把私钥能登上新机
  （2026-10-23 实测通过）。

### 第 2 步（必做）：把改动合到 dev 分支

工作流只在 push dev 时触发。当前改动在 ops/remote 分支的工作区，还没提交。

### 第 3 步（建议做，需要 root）：让前端也能自动部署

不做也能跑，只是**只要前端有改动，部署会主动失败**并告诉你原因（这是故意的，
见下面「为什么前端要卡住」）。做了一次之后前端就全自动了。

```bash
# 把 ops/deploy/install-web-sync.sh 传到服务器后，用 root 执行一次
sudo bash install-web-sync.sh buaa
```

它会装两样东西，都很小：

- /usr/local/sbin/cn-web-sync —— 只做「把 buaa 家目录下的 dist 覆盖到 /var/www/cn」
- /etc/sudoers.d/cn-web-sync —— 只放行这一条命令，没有 ALL，没有通配

脚本内部会再校验：源目录必须在 buaa 家目录下、必须带 index.html、属主必须是 buaa。
所以拿到这把钥匙也只能覆盖站点根目录，不能做别的。

不想给了就 `sudo rm -f /etc/sudoers.d/cn-web-sync`，立刻退回原状。

### 第 4 步（建议做，需要 root）：装上数据库备份定时器

新机目前**一次备份都没跑过**。workflow 里已经把它做成一步，有 sudo 才会执行：

```bash
sudo bash install-backup.sh
```

---

## 三、改完怎么验证

推一次 dev，看运行记录。deploy 这个 job 里会依次出现：

1. 「部署预检（不修改任何文件）」——产物齐、前端要不要换、有没有权限换，一次说清。
   这步红了说明一个字节都没动，可以放心重试。
2. 「换产物并重启」——换了哪些东西、多少字节，`kill -TERM 旧PID → 新PID` 的转换，
   8081/8082/8083 健康码，以及经 nginx 的公开检索。
3. 「上线后冒烟」——从外网入口再打一次，不是 127.0.0.1。

服务器上留了完整日志：`/home/buaa/cn/logs/deploy-last.log`。

---

## 四、出问题怎么回滚

每次部署都会先备份到 `/home/buaa/cn/backup/<时间戳>/`（只留最近 5 次）。

回滚就是把那三个 jar 拷回去，再用同样的方式触发重启：

```bash
cp /home/buaa/cn/backup/20261023-120000/*.jar /home/buaa/cn/app/
kill -TERM $(systemctl show -p MainPID --value cn-core)
```

**Flyway 是唯一会卡住回滚的东西。** core/query 都开着 validate-on-migrate，
如果新 jar 已经把 schema 升到 V24，再用旧 jar 启动会因「Detected applied migration
not resolved locally」直接拒绝启动。这种回滚必须同时：

```sql
DELETE FROM flyway_schema_history WHERE version = '24';
```

线上 query_db 现在在 v6，core_db 在 v23。

---

## 五、这套设计刻意做的取舍

**为什么把 CI 和 CD 拆成两个 job。** 原来一个 job 从头走到尾，机器没了之后整个
job 报红，「测试跑过」这件事被同一个红叉吞掉——10-22 到 10-23 整整两天没人发现
CI 一次都没跑过。拆开之后：build 红 = 代码问题，deploy 红 = 环境问题。

**为什么无 sudo 用 kill -TERM 而不是想办法给 sudo。** unit 里已有
Restart=always + SuccessExitStatus=143 + StartLimitIntervalSec=0，kill 之后 systemd
会立刻拉起，不需要任何特权。代价是**没法用 systemctl 做 start/stop**，所以新 jar
起不来时只能推一个正确的 jar 去顶替，不能「先停掉再慢慢查」。

**为什么要预检，而且前端不一致时直接失败。** 半部署比不部署更难排查：jar 换了、
页面还是旧的，而流水线是绿的。宁可红一次，也不要这种状态。

**为什么 jar 改成 mv 顶替。** 覆盖写会 truncate 正在被 java 读取的 inode。rename
之后旧进程继续持有旧 inode，下次启动才看到新文件——顺带解掉了「换 jar 前必须先停服务」
这条只在 Windows 开发机上成立的限制。

---

## 六、不能宣称的事

- 没有在 GitHub 上真跑过一次（secret 未改）。所有验证都是在本机与生产机上手工执行的，
  包括预检的三个场景（前端一致 / 前端变化无权限 / 显式跳过前端）以及 CI 密钥登录。
- 前端同步助手只做了静态审查与安装脚本自检逻辑，**没有在真机上装过**（需要 root）。
- 部署耗时没有实测。按旧机经验，构建约 80 秒、上传 140MB、重启 60~120 秒
  （三个 Spring 服务串行冷启动）。
