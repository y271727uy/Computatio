实际上这不是mod，只是为了快速工作流而从forge mdk快速创建的

**变动较大，此外不会对任何非支持项目的调用导致的问题负责，目前不应该被任何非支持项目使用**

包含了三个算法(不依赖mc)
快速哈希查找
快速寻路
快速计算
~~斯大林排列~~
等


应该是能用

```groovy
repositories {
    mavenCentral()
    maven {
        url = uri('https://server.cjsah.net:1002/maven/releases')
        mavenContent { releasesOnly() }
        content { includeModule('com.y271727uy', 'computatio') }
    }
    maven {
        url = uri('https://server.cjsah.net:1002/maven/snapshots')
        mavenContent { snapshotsOnly() }
        content { includeModule('com.y271727uy', 'computatio') }
    }
}

dependencies {
    implementation 'com.y271727uy:computatio:0.1.3'
    // 使用快照时，将版本号改为 0.1.3-SNAPSHOT。
}
```

## Maven 发布

在 GitHub 仓库的 **Settings → Secrets and variables → Actions** 中添加两个 Repository secrets：`MAVEN_USERNAME`（仓库账号）和 `MAVEN_PASSWORD`（仓库密码）。该账号需要对两个 Maven 仓库均有发布权限。

- 推送到 `main` 时，GitHub Actions 将 `build.gradle` 中的 `baseVersion` 加上 `-SNAPSHOT` 后发布到 `snapshots` 仓库。
- 发布 GitHub Release 时，Actions 将 `baseVersion` 原样发布到 `releases` 仓库。Release 标签必须为 `v<baseVersion>`，例如版本 `0.1.3` 对应 `v0.1.3`。

