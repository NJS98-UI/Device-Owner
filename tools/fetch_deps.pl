#!/usr/bin/perl
use strict; use warnings;
my $root = "C:/Users/Administrator/Documents/Qoder/2026-09-26/a3a833b3/mgtp/mgtp-a7f38e587bb54791e02e2e49f0dbc3bd6936eae0/libs";
mkdir $root;
my %GOOGLE = map { $_ => 1 } qw(
  androidx.annotation androidx.annotation-jvm androidx.appcompat androidx.appcompat-resources
  androidx.arch.core androidx.cardview androidx.collection androidx.constraintlayout
  androidx.coordinatorlayout androidx.core androidx.cursoradapter androidx.customview
  androidx.drawerlayout androidx.emoji2 androidx.exifinterface androidx.fragment
  androidx.interpolator androidx.lifecycle androidx.loader androidx.recyclerview
  androidx.savedstate androidx.startup androidx.transition androidx.tracing
  androidx.vectordrawable androidx.versionedparcelable androidx.viewpager androidx.viewpager2
  androidx.work androidx.localbroadcastmanager androidx.profileinstaller
  androidx.annotation-experimental androidx.core-animation androidx.window androidx.slidingpanelayout
  com.google.android.material
);
my @DIRECT = (
  ["androidx.appcompat","appcompat","1.7.1"],
  ["com.google.android.material","material","1.13.0"],
  ["androidx.activity","activity","1.12.2"],
  ["androidx.constraintlayout","constraintlayout","2.2.1"],
  ["androidx.recyclerview","recyclerview","1.3.2"],
  ["androidx.cardview","cardview","1.0.0"],
  ["androidx.work","work-runtime","2.9.0"],
  ["androidx.fragment","fragment","1.8.8"],
  ["com.dingtalk.open","app-stream-client","1.3.12"],
  ["com.squareup.okhttp3","okhttp","4.12.0"],
  ["com.squareup.okhttp3","logging-interceptor","4.12.0"],
  ["com.google.code.gson","gson","2.10.1"],
  ["com.google.zxing","core","3.5.1"],
  ["org.nanohttpd","nanohttpd","2.3.1"],
  ["com.github.bumptech.glide","glide","4.16.0"],
  ["io.grpc","grpc-okhttp","1.62.2"],
  ["io.grpc","grpc-stub","1.62.2"],
  ["com.google.protobuf","protobuf-javalite","3.25.3"],
);
my %seen; my @queue = @DIRECT;
my ($gp,$ar) = @_;
sub url_for {
  my ($g,$a,$v,$ext) = @_;
  my $path = join("/", split(/\./,$g)) . "/$a/$v/$a-$v.$ext";
  return ($GOOGLE{$g}
    ? "https://maven.aliyun.com/repository/google/$path"
    : "https://maven.aliyun.com/repository/public/$path");
}
sub url_fallback {
  my ($g,$a,$v,$ext) = @_;
  my $path = join("/", split(/\./,$g)) . "/$a/$v/$a-$v.$ext";
  return ($GOOGLE{$g}
    ? "https://maven.google.com/$path"
    : "https://repo1.maven.org/maven2/$path");
}
sub fetch {
  my ($url,$out) = @_;
  system("curl -sfL --retry 2 -o \"$out\" \"$url\"") == 0;
}
my %pomcache;
while (my $job = shift @queue) {
  my ($g,$a,$v) = @$job;
  my $key = "$g:$a:$v";
  next if $seen{$key}++;
  my $pomf = "$root/$a-$v.pom";
  if (!-f $pomf) {
    my $u = url_for($g,$a,$v,"pom");
    fetch($u,$pomf) or do {
      unlink $pomf;
      fetch(url_fallback($g,$a,$v,"pom"),$pomf)
        or do { unlink $pomf; print "POM-FAIL $key\n"; next; };
    };
  }
  print "POM-OK $key\n";
  open(my $fh,"<",$pomf) or next; local $/; my $pom = <$fh>; close $fh;
  $pom =~ s{<!--.*?-->}{}gs;
  # resolve properties
  my %props;
  while ($pom =~ m{<properties>(.*?)</properties>}gs) {
    my $pb = $1;
    while ($pb =~ m{<([\w.\-]+)>([^<]*)</\1>}g) { $props{$1}=$2; }
  }
  while ($pom =~ m{<dependency>(.*?)</dependency>}gs) {
    my $d = $1;
    my ($dg) = $d =~ m{<groupId>([^<]+)</groupId>};
    my ($da) = $d =~ m{<artifactId>([^<]+)</artifactId>};
    my ($dv) = $d =~ m{<version>([^<]+)</version>};
    my ($scope) = $d =~ m{<scope>([^<]+)</scope>};
    my ($opt) = $d =~ m{<optional>([^<]+)</optional>};
    next if $scope && $scope =~ /test|provided|runtime/;
    next if $opt && $opt =~ /true/;
    next unless $dg && $da;
    next if $dg eq $g && $da eq $a;
    if (defined $dv && $dv =~ /\$\{(.+)\}/) {
      my $p = $1; $dv = $props{$p};
      unless (defined $dv) { print "  SKIP-unresolved \$$p $dg:$da\n"; next; }
    }
    next unless defined $dv;
    $dv =~ s{\s+}{}g;
    print "  DEP $dg:$da:$dv\n";
    push @queue, [$dg,$da,$dv];
  }
}
print "TOTAL artifacts: ", scalar(keys %seen), "\n";
# second pass: download jars/aars
for my $key (keys %seen) {
  my ($g,$a,$v) = split(/:/,$key);
  my $ext = $GOOGLE{$g} ? "aar" : "jar";
  # dingtalk/grpc/opencensus/guava etc are jars; google maven groups are aars except protobuf/grpc under com.google? protobuf-javalite is central jar; guava central jar.
  if ($g =~ /^androidx\.|^com\.google\.android\.material$/) { $ext = "aar"; } else { $ext = "jar"; }
  my $out = "$root/$a-$v.$ext";
  next if -f $out;
  my $u = url_for($g,$a,$v,$ext);
  if (!fetch($u,$out)) { unlink $out;
    fetch(url_fallback($g,$a,$v,$ext),$out) or do { unlink $out;
      $ext = ($ext eq "aar") ? "jar" : "aar";
      $u = url_for($g,$a,$v,$ext); $out = "$root/$a-$v.$ext";
      if (!fetch($u,$out)) { unlink $out; print "JAR-FAIL $key\n"; next; }
    };
  }
  print "JAR-OK $a-$v.$ext\n";
}
