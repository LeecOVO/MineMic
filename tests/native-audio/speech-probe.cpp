// Optional reproducible speech check: pass mono 48kHz PCM16LE synthesized locally
// or provided by the tester. No audio fixture is bundled with the application.
#include "minemic_apm.h"
#include <algorithm>
#include <array>
#include <cassert>
#include <cmath>
#include <cstdio>
#include <fstream>
#include <iterator>
#include <vector>

static void put(uint8_t* p, int n) { p[0]=uint8_t(n);p[1]=uint8_t(uint16_t(n)>>8); }
static int get(const uint8_t* p) { return int16_t(p[0] | uint16_t(p[1])<<8); }
static uint32_t seed=17;
static double noise() {seed=seed*1664525u+1013904223u;return (int32_t(seed)>>16)/32768.;}
static std::vector<double> run(const std::vector<double>& signal) {
    void* apm=mm_apm_create();assert(apm);mm_apm_set_desk_noise(apm,1);
    std::array<uint8_t,960> in{},out{};std::vector<double> result(signal.size());
    for(size_t p=0;p+480<=signal.size();p+=480) {
        for(int i=0;i<480;i++)put(in.data()+2*i,int(std::clamp(signal[p+i],-32768.,32767.)));
        assert(mm_apm_capture(apm,in.data(),out.data(),1,0)==0);
        for(int i=0;i<480;i++) result[p+i]=get(out.data()+2*i);
    }
    mm_apm_destroy(apm);return result;
}
struct Score {double correlation,gain;};
static Score compare(const std::vector<double>& clean,const std::vector<double>& processed,int lag,int stride=1) {
    double cross=0,a=0,b=0;
    for(size_t i=48000+lag;i<clean.size();i+=stride) {double x=clean[i-lag],y=processed[i];cross+=x*y;a+=x*x;b+=y*y;}
    return {cross/std::sqrt(std::max(1.,a*b)),cross/std::max(1.,a)};
}
int main(int argc,char** argv) {
    assert(argc==2);std::ifstream file(argv[1],std::ios::binary);
    std::vector<uint8_t> bytes((std::istreambuf_iterator<char>(file)),{});assert(bytes.size()>96000);
    const auto count=(bytes.size()/960+202)*480;
    std::vector<double> clean(count),mixed(count);
    // Low voice level models a phone resting on a desk. Add transient clicks and
    // intermittent broadband friction without using noise-class labels at runtime.
    for(size_t i=0;i<bytes.size()/2;i++)clean[48000+i]=get(bytes.data()+2*i)*.20;
    for(size_t i=0;i<count;i++) {
        const int phase=i%7200;
        const double click=phase<360 ? std::exp(-phase/65.)*noise()*7000 : 0;
        const double friction=(i/16800)%2 ? noise()*500 : noise()*90;
        mixed[i]=clean[i]+click+friction;
    }
    const auto plain=run(clean), enhanced=run(mixed);
    int lag=0;double best=-1;
    for(int d=0;d<=2400;d+=8) {const auto score=compare(clean,plain,d,8);if(score.correlation>best){lag=d;best=score.correlation;}}
    const int coarse=lag;
    for(int d=std::max(0,coarse-7);d<=coarse+7;d++) {const auto score=compare(clean,plain,d,4);if(score.correlation>best){lag=d;best=score.correlation;}}
    const auto cleanScore=compare(clean,plain,lag), noisyScore=compare(clean,enhanced,lag), inputScore=compare(clean,mixed,0);
    printf("Synthetic speech: clean correlation %.3f / gain %.3f; noisy input correlation %.3f -> output %.3f / gain %.3f; measured DSP lag %.2f ms\n",
        cleanScore.correlation,cleanScore.gain,inputScore.correlation,noisyScore.correlation,noisyScore.gain,lag/48.);
    assert(cleanScore.correlation>.75 && cleanScore.gain>.4);
    assert(noisyScore.correlation>.65 && noisyScore.gain>.35);
    assert(lag<=1440);
    puts("Speech fixture passed; synthesized speech is not real-device acceptance.");
}
