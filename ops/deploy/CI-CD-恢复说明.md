# CI/CD 恢复说明（2026-10-23）

## 一句话

**后端自动部署已通并真跑两轮全绿；前端自动部署卡在「这台机器没有可用的 root」，不是脚本问题。**

---

## 一、现在是什么状态

| 项目 | 状态 |
| --- | --- |
| 构建与测试（verify + 覆盖率门禁 + 前端 build） | 一直好的，没动 |
| CI / CD 结构 | 已拆成 build 与 deploy 两个 job |
| 部署目标 | 168.110.3.136（secrets 已改） |
| 部署账号 | buaa（secrets 已改） |
| 暂存目录 | 连上之后用远端 `$HOME` 推导，不再写死 |
| 部署密钥 | cn-deploy 公钥已授权到新机，指纹 SHA256:7wXsS4W0... |
| 后端自动部署 | **可用**，2026-10-23 真跑两轮全绿（run 39927024752 / 39927835972） |
| 前端自动部署 | **不能**：/var/www/cn 属主 root，buaa 的 sudo 口令是锁死状态（见第八节）。前端源码没变时不误报；变了会显式告警 |
| 数据库自动备份 | **可用**：走 buaa 的 crontab，不需要 root（见第八节） |

---

## 二、如果你接手，需要知道的几件事

### 唯一的门槛：前端上线需要一次 root

其余全部自动。装上 `install-web-sync.sh` 之后前端也会自动，命令见第三节第 3 步。

### 两轮真跑的结果

| run | sha | 关键点 |
| --- | --- | --- |
| 39927024752 | 77f08db | 全绿；前端走退化路径报了「前端待上线」（当时准确：线上是 Windows 构建） |
| 39927835972 | 083ad7b | 全绿；「前端源码未变，跳过」，注解里无前端告警 |

两轮后端都真实换了：PID 依次 76997→116466→123180→124670（core），
8081/8082/8083 全 200，经 nginx 与外网入口的检索都 200。

---

## 三、恢复过程做了什么（已完成，留档）

### 第 1 步：改两个 secret（已完成）

| Secret | 改成 |
| --- | --- |
| CN_SSH_HOST | 168.110.3.136 |
| CN_SSH_USER | buaa |

另外两个**没动**：

- CN_SSH_PORT 保持 22，新机就是 22。
- CN_SSH_KEY 保持原值。已核验：secret 里那把私钥的指纹是
  SHA256:7wXsS4W0OzK58kJ+VgRVzmqF/grj/Pq8TLgljOXvVfQ，对应的公钥已经写进新机的
  authorized_keys，用这把私钥能登上新机（2026-10-23 实测）。

### 第 2 步：仓库改动合到 dev（已完成）

工作流只在 push dev 时触发。改动已推送，见第二节的两轮真跑。

### 第 3 步（建议做，需要 root）：让前端也能自动部署

**先说结论：不做也不会让流水线长期变红。**（2026-10-23 第二次修订，见「判据换成源码版本」）

前端**要不要同步**，现在由 deploy-update.sh 按「前端源码的 git tree hash」判断：
产物里的 `build-info.txt` 记着这次构建对应的 frontend tree，服务器上 `~/cn/state/frontend-tree`
记着上一次真正上线的那一版。两者相同就跳过，不同才需要同步。

这个判据是跨平台一致的，所以**没改前端的那一轮，日志会明确写「前端源码未变，跳过」，不告警**。
上一版不是这样：它比构建产物的文件名，而入口 chunk 的名字会随构建平台变（Linux 出
`index-Dxy1HZYT.js`、Windows 出 `index-CIbzzPOi.js`），于是每轮都判「前端有变化」，每轮都告警。

判出「有变化」而当前身份又写不了 `/var/www/cn` 时，行为是：

- 后端照常更新（改一个前端按钮不该让后端停更）
- 前端产物留在暂存区，等下一次有能力时自动补
- 状态写到三个地方：脚本输出里的 `CN_WEB_PENDING=1`、CI 的 warning 注解、运行页顶部的
  step summary，外加机器上的 `~/cn/logs/deploy-last.log`

要「前端没上线就不算部署成功」的原子语义，跑脚本时传 `CN_REQUIRE_WEB=1`，它会在替换任何
文件之前失败退出。

```bash
# 把 ops/deploy/install-web-sync.sh 传到服务器后，用 root 执行一次
sudo bash install-web-sync.sh buaa
```

这个脚本的同步逻辑已经在真机上演练过 9 条路径全通过（正常同步、覆盖旧版、拒绝家目录之外的源、
拒绝无 index.html、--probe、无 SUDO_USER、权限位可读性），演练方式见第七节。


它会装两样东西，都很小：

- /usr/local/sbin/cn-web-sync —— 只做「把 buaa 家目录下的 dist 覆盖到 /var/www/cn」
- /etc/sudoers.d/cn-web-sync —— 只放行这一条命令，没有 ALL，没有通配

脚本内部会再校验：源目录必须在 buaa 家目录下、必须带 index.html、属主必须是 buaa。
所以拿到这把钥匙也只能覆盖站点根目录，不能做别的。

不想给了就 `sudo rm -f /etc/sudoers.d/cn-web-sync`，立刻退回原状。

### 第 4 步（已完成，不需要 root）：数据库备份

2026-10-23 已落地，走部署账号自己的 crontab，每天 02:20 北京时间跑一次。
明细与首次真跑数据见第八节，此处不再重复。

如果哪天拿到了 root，可以把 cron 那条换成 systemd timer：

```bash
sudo bash install-backup.sh --run-now
crontab -l | grep -v pg-backup.sh | crontab -   # 换掉 cron，避免两份都跑
```

---

## 四、改完怎么验证

推一次 dev，看运行记录。deploy 这个 job 里会依次出现：

1. 「部署预检（不修改任何文件）」——产物齐、前端要不要换、有没有权限换，一次说清。
   这步红了说明一个字节都没动，可以放心重试。
2. 「换产物并重启」——换了哪些东西、多少字节，`kill -TERM 旧PID → 新PID` 的转换，
   8081/8082/8083 健康码，以及经 nginx 的公开检索。
3. 「上线后冒烟」——从外网入口再打一次，不是 127.0.0.1。

服务器上留了完整日志：`/home/buaa/cn/logs/deploy-last.log`。

---

## 五、出问题怎么回滚

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

## 六、这套设计刻意做的取舍

**为什么把 CI 和 CD 拆成两个 job。** 原来一个 job 从头走到尾，机器没了之后整个
job 报红，「测试跑过」这件事被同一个红叉吞掉——10-22 到 10-23 整整两天没人发现
CI 一次都没跑过。拆开之后：build 红 = 代码问题，deploy 红 = 环境问题。

**为什么无 sudo 用 kill -TERM 而不是想办法给 sudo。** unit 里已有
Restart=always + SuccessExitStatus=143 + StartLimitIntervalSec=0，kill 之后 systemd
会立刻拉起，不需要任何特权。代价是**没法用 systemctl 做 start/stop**，所以新 jar
起不来时只能推一个正确的 jar 去顶替，不能「先停掉再慢慢查」。

**为什么要预检。** 半部署比不部署更难排查：jar 换了、页面还是旧的，而流水线是绿的。
预检把「产物齐不齐、前端要不要换、有没有权限换」都提前到第一次写盘之前。

**但「前端没上线」不再等于「整体失败」。** 上一版是整体失败的，理由就是上面那条。
在人工部署时成立，在流水线里不成立：它让「改一个前端按钮」变成「后端也不能更新」，
而且因为没有 root 这条几乎每轮都触发，结果是流水线长期全红，真正的失败被淹掉。

现在把「后端更新」和「前端上线」分开判断，代价是不再原子——所以用三处显式状态补回来：
脚本输出的 `CN_WEB_PENDING=1`、CI 的 warning 注解、运行页顶部的 step summary，
再加机器上的 `deploy-last.log`。要原子语义的场合传 `CN_REQUIRE_WEB=1`。

**为什么前端「要不要同步」要比源码 tree hash，而不是产物文件名。** 走过两次弯路：

1. 第一版比整个 index.html 的 sha256。同一个 lockfile 重建，js/css 文件名由内容哈希决定、
   完全稳定，但 index.html 里注入的 preload 顺序和空白会变 → 每次重建都判「有变化」。
2. 第二版比 index.html 引用的资源文件名集合。在**同一台机器**上重建确实稳定（13 个文件名
   逐字一致），但**跨平台不稳**：同一个 commit，Linux 构建出 `index-Dxy1HZYT.js`，
   Windows 构建出 `index-CIbzzPOi.js`，其余 12 个 chunk 逐字相同。于是 CI 每轮都判
   「前端与线上不一致」，每轮都告警。
3. 现在比 `git rev-parse HEAD:frontend` 算出的 tree hash。跨平台一致、改一个字节就变，
   且只在 git 跟踪的文件上计算，`dist/` 与 `node_modules/` 天然排除。

资源文件名比对仍留在代码里，只作为「产物没带 build-info.txt」时的退化路径，并在日志里
写明「退化」——避免以后有人看到那条路径以为判据本来就是它。


**为什么 jar 改成 mv 顶替。** 覆盖写会 truncate 正在被 java 读取的 inode。rename
之后旧进程继续持有旧 inode，下次启动才看到新文件——顺带解掉了「换 jar 前必须先停服务」
这条只在 Windows 开发机上成立的限制。

---

## 七、已验证 / 未验证

**已验证（2026-10-23 真跑，不是演练）**

- ops-remote（run 39918367846）：runner 连上 instance-20261022-1355（168.110.3.136），
  回传日志里能看到主机名。这一条先跑，因为它不重启任何服务，只验 secrets 与密钥。
- build-and-deploy（run 39920826501）：build 与 deploy 全部成功。
  - 后端 898 个单测通过，4 条覆盖率门禁达标
  - kill -TERM 重启生效：cn-core 76997→116466、cn-query 73222→116604、
    cn-ingestion 80696→116714
  - 8081/8082/8083 全 200，经 nginx 的公开检索 200，外网冒烟 200
  - 启动时 Flyway 报「schema 23 已是最新，无需迁移」
  - 前端按 CN_SKIP_WEB=1 显式跳过（这一版的做法，已被 2026-10-23 的源码 tree 判据取代）

**已验证（2026-10-23 真机演练）**

- cn-web-sync 的同步逻辑：从 install-web-sync.sh 里抽出 HELPER，把站点根指向 /tmp 下
  以 buaa 身份跑，9 条路径全通过——首次同步落地、覆盖旧版（改名让位、无 .old/.new 残留、
  新增文件一起带到）、拒绝家目录之外的源、拒绝无 index.html 的目录、`--probe` 输出、
  无 SUDO_USER 时拒绝、产物权限位满足 others 可读。
- 演练中修掉一个真缺陷：可读性自检里的 `runuser` 没判 `id -u`。runuser 只能由 root 调用，
  非 root 执行必然失败，而失败会被当成「www-data 读不到」→ 白白回退一版。
  这个分支在生产（root）下本来就走对，但条件不完整，离线验证会得出反的结论。
- 前端判据的三条路径都在生产机上验过（`--check`，不写文件）：
  - 源码未变 → 「前端源码未变（frontend tree e05e3eefeacf），跳过前端同步」
  - 源码有变 + 无同步能力 → 警告 + `CN_WEB_PENDING=1`，退出码 0
  - 同样情况 + `CN_REQUIRE_WEB=1` → 退出码 1，且在替换任何文件之前退出

**已验证（2026-10-23 两轮真跑）**

| run | sha | 结果 |
| --- | --- | --- |
| 39927024752 | 77f08db | 全绿。前端走退化路径（服务器上还没有 state 记录）→ 报「前端待上线」warning，这在当时是**准确的**：线上是 Windows 构建，与 CI 的 Linux 构建字节不同 |
| 39927835972 | 083ad7b | 全绿。日志写「前端源码未变，跳过前端同步」，注解里**没有**前端告警 |

两轮的后端都真实换了：PID 123180→124670（core）、123316→124819（query）、
123427→124930（ingestion），8081/8082/8083 全 200，经 nginx 与从外网进来的检索都是 200。

**关于 `~/cn/state/frontend-tree` 的初始化。** 第一轮之所以报「前端待上线」，是因为
服务器上还没有这个记录，脚本退化到「比资源文件名」，而跨平台构建的入口名必然不同。
那个告警在功能层面是假警报：`frontend` 目录自 10-19（a654316）起没有任何提交，
`git rev-parse a654316:frontend` 与当时 HEAD 的 frontend tree 同为 e05e3eef，
线上那 50 个文件就是这份源码构建的。

所以按事实把它初始化成 e05e3eef，并在同级 README.txt 里写清依据。这不是为了让告警消失：
初始化之后，只有 `frontend/` 真的改了才会再判「有变化」。

**未验证**

- install-web-sync.sh 的**安装过程**没有在真机跑过（需要 root）：写 `/usr/local/sbin/cn-web-sync`、
  写 `/etc/sudoers.d/cn-web-sync`、`visudo -c` 校验、以部署账号自检。它的**同步逻辑**
  已按上一条演练过。原因见第八节：**这台机器现在没有任何可用的 root 通路**。
- 数据库备份：已于 2026-10-23 用「部署账号 crontab + TCP + 属主口令」落地并真跑一次，
  见第八节。真正的一次**整库恢复演练**还没做（找不到能建沙箱库的账号）。
- 部署耗时：本次 build 约 4 分钟（Maven 依赖 + 898 个测试），deploy 约 4 分钟
  （上传 140MB + 三个 Spring 服务串行冷启动）。

---

## 八、备份已落地，前端卡在「这台机器没有 root」

### 8.1 先纠正一个旧结论

之前写的是「新机没有装 cn-db-backup.timer」。实际查下来更糟也更具体：

- `/etc/systemd/system/cn-db-backup.{service,timer}` **是存在的**（10-22 那次安装就写了）
- 但 timer 是 `disabled / inactive`
- 且 service 指向的 `/home/buaa/cn-deploy/pg-backup.sh` **不存在**

也就是说：这个单元装了个壳，就算哪天被 enable，每次触发也只会以 203/EXEC 失败。
`systemctl status` 里只是一行红字，很容易一直没人看。

### 8.2 关键发现：buaa 的 sudo 口令是锁死的

```
$ passwd -S
buaa L 10/22/2026 0 99999 7 -1        # L = locked
```

`sudo -n -l` 能列出规则（说明 buaa 在 sudoers 里），但 `sudo` 永远在等一个不存在的口令。
所以「把口令给我」这条路不成立 —— 不是没人知道，是机器上根本没设。

现有的 NOPASSWD 白名单只有 apt update、shared_project 的 chown/chmod、
`systemctl restart nginx` 这几条（另一个项目留下的），都够不到 `/var/www/cn` 与 `/etc/sudoers.d`。
Oracle 的 Run Command 插件也不可用（`oracle-cloud-agent` 是 inactive，而启用它本身要 root）。

### 8.3 于是备份改成不需要 root 的做法

`pg-backup.sh` 现在按运行身份自动选连接方式：

| 身份 | 连接方式 | 用在哪 |
| --- | --- | --- |
| root | `runuser -u postgres`，本地 socket 对等认证 | systemd 单元（将来有 root 时） |
| 普通账号 | TCP 到 127.0.0.1，用该库属主账号 + 口令（从 `~/cn/conf/cn-env.sh` 读） | buaa 自己的 crontab |

部署账号的 crontab 里现在是：

```
20 18 * * * /usr/bin/bash /home/buaa/cn-deploy/pg-backup.sh >> /home/buaa/cn/logs/db-backup.log 2>&1
```

服务器时区是 UTC，18:20 UTC = 北京时间次日 02:20，与原来 timer 的意图一致。
脚本内有 `flock`，所以将来装上 timer 忘了删 cron 也不会两份同时跑。

**改备份脚本之后要注意**：CI 里那一步 `install-backup.sh` 只在有 sudo 时才执行，
所以改了 `ops/deploy/pg-backup.sh` 不会自动落到 `~/cn-deploy/`。没有 root 时手工覆盖：

```bash
scp ops/deploy/pg-backup.sh buaa@168.110.3.136:~/cn-deploy/pg-backup.sh
```

2026-10-23 首次真跑结果（`~/cn/db-backup/20261023-082653/`）：

| 库 | 大小 | 归档条目 | 关键表行数 |
| --- | --- | --- | --- |
| core_db | 103,471,949 | 382 | achievement 27382 / scholar 85047 / contribution 130218 |
| ingestion_db | 21,295,524 | 65 | staged_record 33632 / collect_job 32 |
| query_db | 72,208,267 | 90 | qry_contribution 333962 / qry_coauthor_edge 783616 |

验证方式：把 core_db.dump 取回本地，`pg_restore -a -t core_achievement` 解出 COPY 段，
数据行数 **27382**，与清单记录、与线上 `select count(*)` 三方一致；sha256 记在 manifest 里。

**没做的**：整库恢复演练。`core_owner` 没有 CREATEDB，本地开发库的 postgres 也没有可用口令，
没有地方建一个沙箱库。要真正做这件事，需要一个 `CREATEDB` 的账号。

### 8.4 前端为什么只能继续卡着

`/var/www/cn` 是 root:root，`find /var/www/cn -writable` 对 buaa 返回空；
nginx 的 `root /var/www/cn` 是唯一入口。没有 root 就写不进去，这不是脚本问题。

`install-extras.sh` 仍然保留，它把两件事合成一次 root 操作，且自带 `--check`：

```bash
bash  ~/cn-deploy-scripts/install-extras.sh --check   # 不要 root，只体检
sudo  bash ~/cn-deploy-scripts/install-extras.sh      # 需要 root，装 web-sync + 备份 timer
```

`--check` 在 2026-10-23 实跑，能正确报出「timer 已注册但 disabled」
与「service 指向的脚本不存在」，不影响任何文件。

要拿到那一次 root，现实路径只有控制台一侧：Oracle Cloud 实例的控制台连接（串口）
进单用户模式，或把引导卷挂到另一台机器上改 `sudoers`。两条都要动引导配置，
按你「不要乱改服务器配置」的要求，我没有尝试。

---

## 九、跨平台构建的入口 chunk 差异（已处理，不再误报）

同一个 commit：CI（Linux）构建出的入口是 `index-Dxy1HZYT.js`，本地（Windows）构建出
`index-CIbzzPOi.js`，其余 12 个 chunk 完全同名。各页面代码完全相同，只有入口 chunk
的字节不同——Rollup 的模块遍历顺序受文件系统影响，不是代码变了。

**这条曾经造成一个真缺陷**：判据比的是产物文件名集合，于是 CI 每轮都判「前端有变化」，
每轮都要求同步能力、每轮都告警。真正的失败会淹没在这堆噪音里。

2026-10-23 改为比 `git rev-parse HEAD:frontend` 的 tree hash 之后，这个差异不再影响判断：
源码没变就是没变，与在哪台机器上构建无关。

仍然存在、但不影响部署的一点：**装上 cn-web-sync 后的第一次同步会把线上前端换成
Linux 构建的版本**（此后同平台稳定）。用户看到的页面功能完全相同，只是入口文件名变了。

