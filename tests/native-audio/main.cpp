#include "minemic_apm.h"
#include <array>
#include <cassert>
#include <chrono>
#include <cmath>
#include <cstdio>
#include <cstdint>
#include <vector>

static void put(uint8_t* p, int value) { const auto n=static_cast<uint16_t>(value);p[0]=n;p[1]=n>>8; }
static int sample(const uint8_t* p) { return static_cast<int16_t>(p[0] | uint16_t(p[1])<<8); }
static uint32_t randomState=7;
static double noise() { randomState=randomState*1664525u+1013904223u;return (int32_t(randomState)>>16)/32768.; }
int main() {
 void* apm=mm_apm_create(); assert(apm);
 std::array<uint8_t,960> input{},output{};
 std::array<uint8_t,1920> render{};
 for(int i=0;i<960;i++) input[i]=i;
 assert(mm_apm_capture(apm,input.data(),output.data(),0,0)==0); assert(input==output);
 assert(mm_apm_capture(apm,input.data(),output.data(),0,1)!=0);
 assert(mm_apm_render(nullptr,render.data())!=0);
 double inPower=0,outPower=0;
 for(int block=0;block<500;block++) {
  for(int i=0;i<480;i++) put(input.data()+i*2,int(noise()*2500));
  assert(mm_apm_capture(apm,input.data(),output.data(),1,0)==0);
  if(block>200) for(int i=0;i<480;i++) {double a=sample(input.data()+2*i), b=sample(output.data()+2*i);inPower+=a*a;outPower+=b*b;}
 }
 const double nsDb=10*std::log10(inPower/std::max(1.,outPower));
 std::printf("Stationary synthetic noise attenuation: %.2f dB\n",nsDb);assert(nsDb>3.0);
 mm_apm_destroy(apm);apm=mm_apm_create();
 std::vector<int> left(480*1200),right(left.size());
 double smoothL=0,smoothR=0;
 for(size_t i=0;i<left.size();i++) {smoothL=.6*smoothL+.4*noise();smoothR=.6*smoothR+.4*noise();left[i]=int(smoothL*11000);right[i]=int(smoothR*11000);}
 inPower=outPower=0;
 const auto started=std::chrono::steady_clock::now();
 for(int block=0;block<1200;block++) {
  for(int i=0;i<480;i++) {
   const int t=block*480+i;
   put(render.data()+4*i,left[t]);put(render.data()+4*i+2,right[t]);
   const int echo=t>=2400 ? int(.5*left[t-2400]+.3*right[t-2400]+.08*left[t-2500 < 0 ? 0 : t-2500]) : 0;
   put(input.data()+2*i,echo);
  }
  assert(mm_apm_render(apm,render.data())==0);
  assert(mm_apm_capture(apm,input.data(),output.data(),0,1)==0);
  if(block>900) for(int i=0;i<480;i++) {double a=sample(input.data()+2*i),b=sample(output.data()+2*i);inPower+=a*a;outPower+=b*b;}
 }
 const auto elapsed=std::chrono::duration<double,std::milli>(std::chrono::steady_clock::now()-started).count();
 const double aecDb=10*std::log10(inPower/std::max(1.,outPower));
 std::printf("Synthetic stereo echo attenuation: %.2f dB; host mean render+capture %.3f ms/frame\n",aecDb,elapsed/1200);
 assert(aecDb>10.0);
 // Double talk: cancellation must retain an independent near-end voiced signal.
 std::vector<double> near(480*300), processed(near.size());
 for(int block=0;block<300;block++) {
  for(int i=0;i<480;i++) {
   const int t=block*480+i, far=t % int(left.size());
   const double seconds=t/48000.;
   near[t]=(0.55+0.45*std::sin(seconds*7.1))*4500*(std::sin(seconds*2*3.141592653589793*173)+.35*std::sin(seconds*2*3.141592653589793*346));
   put(render.data()+4*i,left[far]);put(render.data()+4*i+2,right[far]);
   const int old=(far+int(left.size())-2400)%int(left.size());
   put(input.data()+2*i,int(near[t]+.5*left[old]+.3*right[old]));
  }
  assert(mm_apm_render(apm,render.data())==0);
  assert(mm_apm_capture(apm,input.data(),output.data(),0,1)==0);
  for(int i=0;i<480;i++) processed[block*480+i]=sample(output.data()+2*i);
 }
 double best=0,bestGain=0;
 for(int delay=0;delay<=960;delay++) {
  double cross=0,a=0,b=0;
  for(size_t t=48000;t<near.size();t++) {cross+=near[t-delay]*processed[t];a+=near[t-delay]*near[t-delay];b+=processed[t]*processed[t];}
  const auto correlation=cross/std::sqrt(std::max(1.,a*b));
  if(correlation>best) {best=correlation;bestGain=cross/std::max(1.,a);}
 }
 std::printf("Synthetic double-talk near-end correlation %.3f, gain %.3f\n",best,bestGain);
 assert(best>.5 && bestGain>.25 && bestGain<1.8);
 mm_apm_reset_echo(apm);assert(mm_apm_capture(apm,input.data(),output.data(),0,1)!=0);
 mm_apm_destroy(apm);
 // Desktop-game profile: impulsive keyboard/mouse-like bursts plus broadband
 // friction. These synthetic fixtures validate DSP behavior, not device quality.
 apm=mm_apm_create();assert(apm);mm_apm_set_desk_noise(apm,1);
 assert(mm_apm_capture(apm,input.data(),output.data(),0,0)==0);assert(input==output);
 inPower=outPower=0;
 const auto neuralStart=std::chrono::steady_clock::now();
 for(int block=0;block<600;block++) {
  for(int i=0;i<480;i++) {
   const int phase=(block*480+i)%7200;
   const double click=phase<360 ? std::exp(-phase/65.)*noise()*14000 : 0;
   const double friction=(block/35)%2 ? noise()*900 : 0;
   put(input.data()+2*i,int(click+friction));
  }
  assert(mm_apm_capture(apm,input.data(),output.data(),1,0)==0);
  if(block>100) for(int i=0;i<480;i++) {double a=sample(input.data()+2*i),b=sample(output.data()+2*i);inPower+=a*a;outPower+=b*b;}
 }
 const double neuralMs=std::chrono::duration<double,std::milli>(std::chrono::steady_clock::now()-neuralStart).count()/600;
 const double deskDb=10*std::log10(inPower/std::max(1.,outPower));
 printf("Synthetic click/friction attenuation %.2f dB; host RNNoise mean %.3f ms/frame\n",deskDb,neuralMs);
 assert(deskDb>3.0);
 // Switching profiles keeps reference-free bypass bit exact and can re-enter NS.
 mm_apm_set_desk_noise(apm,0);assert(mm_apm_capture(apm,input.data(),output.data(),0,0)==0);assert(input==output);
 assert(mm_apm_capture(apm,input.data(),output.data(),1,0)==0);
 mm_apm_destroy(apm);puts("Native WebRTC/RNNoise regressions passed (synthetic, not device acceptance)");
}
