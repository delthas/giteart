# giteart [![builds.sr.ht status](https://builds.sr.ht/~delthas/giteart.svg)](https://builds.sr.ht/~delthas/giteart?)

**trigger `sr.ht` builds with Gitea webhooks**

## [download](https://delthas.fr/giteart.jar)

## usage

### giteart daemon

- [generate an sr.ht token](https://meta.sr.ht/oauth/personal-token)
- edit `giteart.example.yml` into `giteart.yml`
- run giteart (`java -jar giteart.jar` or `java -jar giteart.jar /path/to/giteart.yml`, defaults to `giteart.yml`)
- keep gitart running (you could typically use a systemd unit)

### webhook

- make sure your repository is public
- go to your repository webhooks settings and add a new *gitea* webhook (page url is `https://path/to/repository/settings/hooks/gitea/new`)
  - `target URL` is `http://your.server:port/hook`, where port is your configuration port
  - `secret` is your configuration secret

### manifest

- [add a build manifest to your repository](https://man.sr.ht/builds.sr.ht/#build-manifests) 
- commit and push
- [check a build was queued](https://builds.sr.ht/)
