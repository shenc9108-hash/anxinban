# 用域名访问后端

IP 会变的核心做法：让域名指向后端，孩子端和家长端都只认域名。

## 方案 A（推荐，最稳）：买一台云服务器 + 域名
1. 买云服务器（阿里云、腾讯云轻量应用服务器，一年几十到一百多元）和一个域名。
   - 服务器在中国大陆：域名要先做 ICP 备案，周期一到三周。
   - 服务器在香港或海外：不用备案，但国内访问略慢。
2. 域名解析里加一条 A 记录：guard.你的域名.com -> 服务器公网 IP。服务器 IP 固定，不会变。
3. 服务器上装 Node 18+，上传 server 文件夹，用 `PARENT_KEY=你的密码 node server.js` 启动（建议用 pm2 保持常驻）。
4. 装 Caddy 自动申请 https 证书：安装后把本目录的 Caddyfile 里的域名改成你的，运行 `caddy run`。
5. 家长端：浏览器打开 https://guard.你的域名.com。孩子端：服务器地址填 guard.你的域名.com。

## 方案 B：后端跑在家里电脑上（便宜，但有门槛）
- 家宽动态 IP：用 DDNS，把域名自动指向当前公网 IP。DNSPod、阿里云解析、Cloudflare 都有 DDNS 脚本或路由器自带的 DDNS 功能。
- 很多宽带是大内网，没有公网 IP，DDNS 无效。可用 Cloudflare Tunnel 或 frp 内网穿透。
- 电脑必须一直开机联网，孩子出门在 4G/5G 下才能同步。

## 注意
- 手机在外面用移动网络时，只有公网域名能访问，局域网 IP 不行。
- 公网部署前务必改掉默认家长密钥，并只用 https。

## 一键部署（Debian / Ubuntu 服务器）
1. Cloudflare 里给域名加 A 记录：名称 shouhu，内容填服务器 IP，代理状态先选“仅 DNS”（灰云）。
2. 电脑上把 Anxinban.zip 传到服务器并解压：
       scp Anxinban.zip root@服务器IP:/root/
       ssh root@服务器IP
       apt-get install -y unzip && unzip Anxinban.zip
3. 运行：bash Anxinban/server/deploy.sh 你的域名 你想设的家长密钥
4. 浏览器打开 https://你的域名。以后更新代码：重新上传并再运行一次脚本即可。
