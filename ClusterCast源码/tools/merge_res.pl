#!/usr/bin/perl
# Merge res/ dirs (lowest->highest priority, argv order; last dir = ours) into out dir.
# values* dirs: dedupe top-level <resources> children by tag+name, later wins.
# other dirs: file copy, later overwrites.
use strict; use warnings; use File::Path qw(make_path remove_tree); use File::Copy;
use File::Find; use File::Basename;

my @dirs = @ARGV;
my $out = pop @dirs;
remove_tree($out) if -d $out;
make_path($out);

sub parse_top {
    my ($xml) = @_;
    $xml =~ s/<!--.*?-->//gs;
    return () unless $xml =~ /<resources[^>]*>/;
    my $body = substr($xml, $+[0], rindex($xml, '</resources>') - $+[0]);
    my @out; my $i = 0;
    while ($i < length $body) {
        my $c = substr($body, $i, 1);
        if ($c ne '<') { $i++; next; }
        substr($body, $i) =~ /^<([A-Za-z0-9_-]+)/ or $i++, next;
        my $tag = $1;
        my ($close, $selfc);
        my $k = $i;
        while ($k < length $body) {
            my $ch = substr($body, $k, 1);
            if ($ch eq '"') { my $q = index($body, '"', $k+1); last if $q < 0; $k = $q + 1; next; }
            if ($ch eq '>') { $close = $k; last; }
            $k++;
        }
        last unless defined $close;
        my $head = substr($body, $i, $close - $i + 1);
        if ($head =~ m{/>\s*$}) {
            push @out, substr($body, $i, $close - $i + 1); $i = $close + 1; next;
        }
        my $rest = substr($body, $close + 1);
        last unless $rest =~ m{</$tag\s*>};
        my $end = $close + 1 + $+[0];
        push @out, substr($body, $i, $end - $i);
        $i = $end;
    }
    return grep { /\S/ } @out;
}

sub key_of {
    my ($el) = @_;
    my ($tag) = $el =~ /^<([A-Za-z0-9_-]+)/;
    my ($nm) = $el =~ /\bname="([^"]*)"/;
    return "$tag:" . (defined $nm ? $nm : '');
}

my %values;      # dirname -> { key -> element }
my %xmlns;       # collected namespace declarations
my @files;       # [src, relpath] low->high

for my $dir (@dirs) {
    next unless -d $dir;
    opendir my $dh, $dir or next;
    for my $e (sort readdir $dh) {
        next if $e eq '.' || $e eq '..';
        my $sub = "$dir/$e";
        if (-d $sub) {
            if ($e =~ /^values/) {
                my $m = $values{$e} ||= {};
                opendir my $vh, $sub or next;
                for my $f (sort readdir $vh) {
                    next unless $f =~ /\.xml$/;
                    open my $fh, '<', "$sub/$f" or next;
                    local $/; my $xml = <$fh>; close $fh;
                    $xml = '' unless defined $xml;
                    utf8::decode($xml);
                    $xmlns{$1} = $2 while $xml =~ /\s(xmlns:[A-Za-z0-9_]+)="([^"]*)"/g;
                    $m->{key_of($_)} = $_ for parse_top($xml);
                }
            } else {
                find({ wanted => sub { push @files, [$File::Find::name, $dir] if -f $File::Find::name }, no_chdir => 1 }, $sub);
            }
        }
    }
}

for my $f (@files) {
    my ($src, $root) = @$f;
    (my $rel = $src) =~ s/^\Q$root\E[\\\/]//;
    my $dst = "$out/$rel";
    $dst =~ s/\\/\//g;
    make_path(dirname($dst));
    copy($src, $dst) or die "copy $src: $!";
}
for my $dn (sort keys %values) {
    my $d = "$out/$dn";
    make_path($d);
    open my $fh, '>', "$d/values.xml" or die $!;
    binmode $fh, ':utf8';
    print $fh "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<resources";
    while (my ($k, $v) = each %xmlns) { print $fh " $k=\"$v\""; }
    print $fh ">\n";
    print $fh join("\n", values %{$values{$dn}}), "\n";
    print $fh "</resources>\n";
    close $fh;
}
my $nv = scalar keys %values;
print "merged " . scalar(@dirs) . " res dirs -> $out ($nv values dirs, " . scalar(@files) . " files)\n";
