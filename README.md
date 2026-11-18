实际上这不是mod，只是为了快速工作流而从forge mdk快速创建的

包含了三个算法(不依赖mc)
快速哈希查找
快速寻路
快速计算

## 通过 GitHub Packages 使用

在消费项目的 `build.gradle` 中添加仓库和依赖。将 GitHub 用户名和具有
`read:packages` 权限的个人访问令牌（classic）分别设置为
`GITHUB_PACKAGES_USER` 和 `GITHUB_PACKAGES_TOKEN` 环境变量。

```groovy
repositories {
    mavenCentral()
    maven {
        url = uri('https://maven.pkg.github.com/y271727uy/Computatio')
        content {
            includeModule('com.y271727uy', 'computatio')
        }
        credentials {
            username = providers.environmentVariable('GITHUB_PACKAGES_USER').orNull
            password = providers.environmentVariable('GITHUB_PACKAGES_TOKEN').orNull
        }
    }
}

dependencies {
    implementation 'com.y271727uy:computatio:0.1.0'
}
```

## 发布版本

在 `build.gradle` 中设置版本号，然后在 GitHub 发布对应的 Release。
标签格式为 `v<版本号>`，例如 `v0.1.0`。Release 发布后，GitHub Actions
使用仓库的 `GITHUB_TOKEN` 将库、源码和 Maven 元数据上传到 GitHub Packages。
