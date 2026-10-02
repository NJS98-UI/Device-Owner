#!/usr/bin/perl
# Extract hub methods from EVCam MainActivity, adapt, emit java fragment to stdout.
use strict; use warnings;

my $M = '/tmp/evcam/app/src/main/java/com/kooo/evcam/MainActivity.java';
open my $F, '<', $M or die $!; my @L = <$F>; close $F;
chomp @L;

sub extract {
    my ($name) = @_;
    for my $i (0..$#L) {
        next unless $L[$i] =~ /^\s{4}(?:public|private|protected|static)[^;{]*\b\Q$name\E\s*\(/;
        # brace match
        my $depth = 0; my $started = 0; my @out;
        for my $j ($i..$#L) {
            my $l = $L[$j];
            my $open = () = $l =~ /\{/g;
            my $close = () = $l =~ /\}/g;
            push @out, $l;
            $depth += $open - $close;
            $started = 1 if $open > 0;
            last if $started && $depth <= 0;
        }
        return join("\n", @out);
    }
    die "method not found: $name\n";
}

my @methods = @ARGV;
for my $m (@methods) {
    my $code = extract($m);
    $code =~ s/\bcameraManager\b/mcm/g;
    $code =~ s/\bMainActivity\.this\.isRecording\b/quadRecording/g;
    $code =~ s/\bthis\.isRecording\b/quadRecording/g;
    $code =~ s/(?<![\w.])isRecording\b/quadRecording/g;
    $code =~ s/(?<![\w.])isRemoteRecording\b/remoteRecording/g;
    # qualify types
    my %q = (
        'DingTalkStreamManager' => 'com.kooo.evcam.dingtalk.DingTalkStreamManager',
        'DingTalkApiClient'     => 'com.kooo.evcam.dingtalk.DingTalkApiClient',
        'DingTalkConfig'        => 'com.kooo.evcam.dingtalk.DingTalkConfig',
        'TelegramBotManager'    => 'com.kooo.evcam.telegram.TelegramBotManager',
        'TelegramApiClient'     => 'com.kooo.evcam.telegram.TelegramApiClient',
        'TelegramConfig'        => 'com.kooo.evcam.telegram.TelegramConfig',
        'FeishuApiClient'       => 'com.kooo.evcam.feishu.FeishuApiClient',
        'FeishuBotManager'      => 'com.kooo.evcam.feishu.FeishuBotManager',
        'FeishuConfig'          => 'com.kooo.evcam.feishu.FeishuConfig',
        'RemoteServiceManager'  => 'com.kooo.evcam.RemoteServiceManager',
        'RemoteCommandDispatcher' => 'com.kooo.evcam.remote.RemoteCommandDispatcher',
        'ImageAdjustManager'    => 'com.kooo.evcam.camera.ImageAdjustManager',
        'StorageCleanupManager' => 'com.kooo.evcam.StorageCleanupManager',
        'FloatingWindowService' => 'com.kooo.evcam.FloatingWindowService',
        'PreviewCorrectionFloatingWindow' => 'com.kooo.evcam.PreviewCorrectionFloatingWindow',
        'FisheyeCorrectionFloatingWindow' => 'com.kooo.evcam.FisheyeCorrectionFloatingWindow',
        'AppConfig'             => 'com.kooo.evcam.AppConfig',
    );
    for my $k (keys %q) {
        $code =~ s/(?<![\w.])\Q$k\E\b/$q{$k}/g;
    }
    print "// ==== $m (from EVCam MainActivity) ====\n$code\n\n";
}
