实际上这不是mod，只是为了快速工作流而从forge mdk快速创建的

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
        url = uri('https://y271727uy.github.io/Computatio/')
        content {
            includeModule('com.y271727uy', 'computatio')
        }
    }
}

dependencies {
    implementation 'com.y271727uy:computatio:0.1.3'
}
```

