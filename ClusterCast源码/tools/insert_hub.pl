#!/usr/bin/perl
use strict; use warnings;
my $root = $ARGV[0] or die "usage: insert_hub.pl <project-root>\n";
chdir $root or die "chdir: $!";

open my $pre, '<', 'tools/hub_prelude.java' or die $!;
local $/; my $prelude = <$pre>; close $pre;
open my $hub, '<', '/tmp/hub_methods.java' or die $!;
my $methods = <$hub>; close $hub;

my $main = 'src/com/jietu/clustercast/MainActivity.java';
open my $m, '<', $main or die $!;
my $src = <$m>; close $m;

die "hub already inserted" if $src =~ /EVCam hub：字段与桥接/;

my $block = $prelude . $methods;

# insert before startQuad definition
my $anchor = "    private void startQuad() {";
my $pos = index($src, $anchor);
die "startQuad anchor not found" if $pos < 0;
substr($src, $pos, 0) = $block;

# onCreate: init instance + configs after cfg = new Cfg(this);
my $c1 = "        cfg = new Cfg(this);";
my $p1 = index($src, $c1);
die "cfg anchor not found" if $p1 < 0;
my $init = <<'EOI';
        cfg = new Cfg(this);
        instance = this;
        appConfig = new com.kooo.evcam.AppConfig(this);
        dingTalkConfig = new com.kooo.evcam.dingtalk.DingTalkConfig(this);
        telegramConfig = new com.kooo.evcam.telegram.TelegramConfig(this);
        feishuConfig = new com.kooo.evcam.feishu.FeishuConfig(this);
EOI
substr($src, $p1, length($c1)) = $init;

# onResume: isInBackground = false
my $c2 = "    \@Override protected void onResume() {\n        super.onResume();";
my $p2 = index($src, $c2);
die "onResume anchor not found" if $p2 < 0;
substr($src, $p2, length($c2)) = "    \@Override protected void onResume() {\n        super.onResume();\n        isInBackground = false;";

# onPause: isInBackground = true
my $c3 = "    \@Override protected void onPause() {\n        super.onPause();";
my $p3 = index($src, $c3);
die "onPause anchor not found" if $p3 < 0;
substr($src, $p3, length($c3)) = "    \@Override protected void onPause() {\n        super.onPause();\n        isInBackground = true;";

# import AppLog after import com.kooo.evcam.StorageHelper;
my $c4 = "import com.kooo.evcam.StorageHelper;";
my $p4 = index($src, $c4);
die "import anchor not found" if $p4 < 0;
substr($src, $p4 + length($c4), 0) = "\nimport com.kooo.evcam.AppLog;";

open my $o, '>', $main or die $!;
print $o $src;
close $o;
print "HUB INSERTED\n";
