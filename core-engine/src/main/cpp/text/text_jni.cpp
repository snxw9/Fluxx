#include "font_manager.h"
#include "font_catalog.h"
#include "prepared_text_packet.h"
#include "font_inspection.h"
#ifdef FLUXX_TEXT_DIAGNOSTICS
#include "text_diagnostics.h"
#endif
#include <jni.h>
#include <android/asset_manager_jni.h>
#include <map>
#include <mutex>
#include <stdexcept>
#include <unistd.h>
#include <cmath>
#include "paragraph_layout.h"
#include <algorithm>
#include <list>
#include <chrono>

namespace {
using fluxx::text::FontManager;
std::mutex registryMutex;
std::map<jlong, std::shared_ptr<FontManager>> sessions;
std::weak_ptr<FontManager> catalog;
thread_local jlong isolatedTimings[3]={};
jlong nextSession = 1;
void fail(JNIEnv* env, const std::exception& error) {
    if (!env->ExceptionCheck()) env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), error.what());
}

std::shared_ptr<FontManager> get(jlong id) {
    std::lock_guard<std::mutex> lock(registryMutex);
    const auto found = sessions.find(id);
    if (found == sessions.end()) throw std::invalid_argument("Font session is closed");
    return found->second;
}
std::string string(JNIEnv* env, jstring value) {
    if (!value || env->GetStringLength(value) > 4096) throw std::invalid_argument("Invalid string argument");
    const char* chars = env->GetStringUTFChars(value, nullptr);
    if (!chars) throw std::bad_alloc();
    std::string result(chars);
    env->ReleaseStringUTFChars(value, chars);
    return result;
}
std::vector<uint8_t> asset(AAssetManager* manager, const char* name) {
    std::unique_ptr<AAsset, decltype(&AAsset_close)> input(AAssetManager_open(manager, name, AASSET_MODE_BUFFER), AAsset_close);
    if (!input) throw std::runtime_error(std::string("Missing bundled font: ") + name);
    const auto length = AAsset_getLength64(input.get());
    if (length <= 0 || length > 16 * 1024 * 1024) throw std::runtime_error("Invalid font asset length");
    std::vector<uint8_t> bytes(static_cast<size_t>(length));
    size_t at = 0;
    while (at < bytes.size()) {
        const int count = AAsset_read(input.get(), bytes.data() + at, bytes.size() - at);
        if (count <= 0) throw std::runtime_error("Incomplete font asset read");
        at += static_cast<size_t>(count);
    }
    return bytes;
}
}

extern "C" JNIEXPORT jbyteArray JNICALL Java_com_fluxx_android_engine_IsolatedFontNative_prepare(JNIEnv* env,jobject,
    jobject assets,jobjectArray ids,jobjectArray bytes,jintArray faceIndices,jintArray instanceIndices,
    jobjectArray axisTags,jobjectArray axisValues,jbyteArray input,jboolean ligatures,jboolean nominal,jbyteArray seeds) {
    try {
        const auto uid=getuid()%100000;
        if(uid<90000 || uid>99999) throw std::invalid_argument("Imported font preparation requires an isolated process");
        if(!ids || !bytes || !faceIndices || !instanceIndices || !axisTags || !axisValues || !input)
            throw std::invalid_argument("Missing font preparation arguments");
        const auto count=env->GetArrayLength(ids);
        if(count>64 || env->GetArrayLength(bytes)!=count || env->GetArrayLength(faceIndices)!=count ||
            env->GetArrayLength(instanceIndices)!=count || env->GetArrayLength(axisTags)!=count || env->GetArrayLength(axisValues)!=count)
            throw std::invalid_argument("Prepared text supports at most 64 imported font identities");
        if(env->GetArrayLength(input)>1024*1024) throw std::invalid_argument("Styled input exceeds 1 MiB");
        std::vector<jint> faces(count),instances(count);
        if(count>0) { env->GetIntArrayRegion(faceIndices,0,count,faces.data());env->GetIntArrayRegion(instanceIndices,0,count,instances.data()); }
        std::vector<fluxx::text::FontInput> fonts;size_t total=0;
        auto* source=AAssetManager_fromJava(env,assets);
        if(!source) throw std::invalid_argument("AssetManager is null");
        const char* families[]={"fluxx.sans","fluxx.serif","fluxx.mono"};
        const char* files[]={"Inter","NotoSerif","JetBrainsMono"};
        const char* styles[]={"Regular","Bold","Italic","BoldItalic"};
        const char* suffixes[]={"",".bold",".italic",".bold-italic"};
        for(unsigned family=0;family<3;++family) for(unsigned style=0;style<4;++style)
            fonts.push_back({std::string(families[family])+suffixes[style],asset(source,(std::string("fonts/")+files[family]+"-"+styles[style]+".ttf").c_str())});
        for(jsize i=0;i<count;++i) {
            const auto identity=static_cast<jstring>(env->GetObjectArrayElement(ids,i));
            const auto data=static_cast<jbyteArray>(env->GetObjectArrayElement(bytes,i));
            const auto tags=static_cast<jstring>(env->GetObjectArrayElement(axisTags,i));
            const auto values=static_cast<jfloatArray>(env->GetObjectArrayElement(axisValues,i));
            if(!data || !tags || !values) throw std::invalid_argument("Missing imported font arguments");
            const auto length=env->GetArrayLength(data);const auto axes=env->GetArrayLength(values);
            if(length<=0 || length>16*1024*1024 || axes>16 || faces[i]<0 || faces[i]>255 || instances[i]<-1 || instances[i]>255)
                throw std::invalid_argument("Invalid imported font limits");
            total+=static_cast<size_t>(length);if(total>32*1024*1024) throw std::invalid_argument("Imported font preparation exceeds 32 MiB; reduce distinct font faces");
            fluxx::text::FontInput font;font.id=string(env,identity);font.faceIndex=static_cast<unsigned>(faces[i]);font.namedInstance=instances[i];
            font.bytes.resize(length);env->GetByteArrayRegion(data,0,length,reinterpret_cast<jbyte*>(font.bytes.data()));
            const auto tagString=string(env,tags);if(tagString.size()!=static_cast<size_t>(axes)*4) throw std::invalid_argument("Invalid variation tags");
            std::vector<jfloat> coordinates(axes);if(axes>0) env->GetFloatArrayRegion(values,0,axes,coordinates.data());
            for(jsize a=0;a<axes;++a) {
                uint32_t tag=0;for(unsigned b=0;b<4;++b) { const auto c=static_cast<unsigned char>(tagString[static_cast<size_t>(a)*4+b]);
                    if(c<32 || c>126) throw std::invalid_argument("Invalid variation tag");
                    tag=(tag<<8)|c; }
                font.variations.emplace_back(tag,coordinates[a]);
            }
            fonts.push_back(std::move(font));env->DeleteLocalRef(identity);env->DeleteLocalRef(data);env->DeleteLocalRef(tags);env->DeleteLocalRef(values);
        }
        if(env->ExceptionCheck()) return nullptr;
        // Preparation only. No imported parser handles or bytes enter the application renderer.
        // Calls are serialized by the isolated worker. Retain exact face/variation identities
        // while its binding is warm, so repeated typing only rasterizes missing glyphs.
        struct WarmManager { std::string identity;size_t bytes;std::shared_ptr<FontManager> manager; };
        static std::list<WarmManager> warm;
        std::string identity;size_t owned=0;
        for(const auto& font:fonts) { identity+=font.id;identity.push_back('\0');owned+=font.bytes.size(); }
        auto cached=std::find_if(warm.begin(),warm.end(),[&](const WarmManager& item) {return item.identity==identity;});
        std::shared_ptr<FontManager> manager;
        if(cached!=warm.end()) { manager=cached->manager;warm.splice(warm.end(),warm,cached); }
        else {
            manager=std::make_shared<FontManager>(std::move(fonts));
            warm.push_back({identity,owned,manager});
            size_t totalBytes=0;for(const auto& item:warm) totalBytes+=item.bytes;
            while(warm.size()>1 && (warm.size()>4 || totalBytes>64*1024*1024)) {totalBytes-=warm.front().bytes;warm.pop_front();}
        }
        if(seeds) {
            const auto length=env->GetArrayLength(seeds);if(length>8*1024*1024) throw std::invalid_argument("SDF seeds exceed 8 MiB");
            std::vector<uint8_t> cachedSdfs(length);if(length) env->GetByteArrayRegion(seeds,0,length,reinterpret_cast<jbyte*>(cachedSdfs.data()));
            if(env->ExceptionCheck()) return nullptr;
            manager->seedSdfs(fluxx::text::decodeSdfSeeds(cachedSdfs.data(),cachedSdfs.size()));
        }
        std::vector<uint8_t> wire(static_cast<size_t>(env->GetArrayLength(input)));
        env->GetByteArrayRegion(input,0,static_cast<jsize>(wire.size()),reinterpret_cast<jbyte*>(wire.data()));
        if(env->ExceptionCheck()) return nullptr;
        const auto styled=fluxx::text::decodeStyledInput(wire.data(),wire.size());
        if(nominal && styled.runs.size()!=1) throw std::invalid_argument("Nominal catalogue requires one font run");
        const auto before=manager->stats();const auto shapingStart=std::chrono::steady_clock::now();
        const auto layout=nominal?manager->nominal(styled.runs[0].fontId,styled.text):
            manager->shapeStyled("fluxx.sans",styled.text,styled.runs,styled.uppercase,ligatures);
        isolatedTimings[0]=std::chrono::duration_cast<std::chrono::nanoseconds>(std::chrono::steady_clock::now()-shapingStart).count();
        const auto packetStart=std::chrono::steady_clock::now();
        const auto packet=fluxx::text::encodePreparedText(*layout,*manager);
        isolatedTimings[1]=static_cast<jlong>(manager->stats().rasterNs-before.rasterNs);
        isolatedTimings[2]=std::max<jlong>(0,std::chrono::duration_cast<std::chrono::nanoseconds>(std::chrono::steady_clock::now()-packetStart).count()-isolatedTimings[1]);
        const auto result=env->NewByteArray(static_cast<jsize>(packet.size()));
        if(result) env->SetByteArrayRegion(result,0,static_cast<jsize>(packet.size()),reinterpret_cast<const jbyte*>(packet.data()));
        return result;
    } catch(const std::exception& error) { fail(env,error);return nullptr; }
}
extern "C" JNIEXPORT jlongArray JNICALL Java_com_fluxx_android_engine_IsolatedFontNative_timings(JNIEnv* env,jobject) {
    const auto result=env->NewLongArray(3);if(result) env->SetLongArrayRegion(result,0,3,isolatedTimings);return result;
}
std::shared_ptr<FontManager> fluxx::text::acquireFontCatalog(AAssetManager* source) {
    std::lock_guard<std::mutex> lock(registryMutex);
    if (!source) throw std::invalid_argument("AssetManager is null");
    auto manager = catalog.lock();
    if (!manager) {
        std::vector<FontInput> fonts;
        fonts.push_back({"fluxx.sans", asset(source, "fonts/Inter-Regular.ttf")});
        fonts.push_back({"fluxx.serif", asset(source, "fonts/NotoSerif-Regular.ttf")});
        fonts.push_back({"fluxx.mono", asset(source, "fonts/JetBrainsMono-Regular.ttf")});
        const char* families[] = {"fluxx.sans", "fluxx.serif", "fluxx.mono"};
        const char* files[] = {"Inter", "NotoSerif", "JetBrainsMono"};
        const char* styles[] = {"Bold", "Italic", "BoldItalic"};
        const char* suffixes[] = {".bold", ".italic", ".bold-italic"};
        for (unsigned family=0; family<3; ++family) for (unsigned style=0; style<3; ++style)
            fonts.push_back({std::string(families[family])+suffixes[style],
                asset(source, (std::string("fonts/")+files[family]+"-"+styles[style]+".ttf").c_str())});
        manager = std::make_shared<FontManager>(std::move(fonts));
        catalog = manager;
    }
    return manager;
}

extern "C" JNIEXPORT jlong JNICALL Java_com_fluxx_android_engine_TextNative_create(JNIEnv* env, jobject, jobject assets) {
    try {
        auto manager = fluxx::text::acquireFontCatalog(AAssetManager_fromJava(env, assets));
        std::lock_guard<std::mutex> lock(registryMutex);
        if (nextSession == INT64_MAX) throw std::runtime_error("Font session IDs exhausted");
        const jlong id = nextSession++; sessions.emplace(id, manager); return id;
    } catch (const std::exception& error) { fail(env, error); return 0; }
}
extern "C" JNIEXPORT jlong JNICALL Java_com_fluxx_android_engine_TextNative_warmGlyph(JNIEnv* env,jobject,jlong session,jstring font,jint cp) {
    try {
        if(cp<32 || cp>255 || (cp>126 && cp<160)) throw std::invalid_argument("Unsupported warm scalar");
        auto manager=get(session);const auto id=string(env,font);const auto glyph=manager->glyphForCodepoint(id,cp);
        return glyph?static_cast<jlong>(manager->sdf(id,glyph)->pixels.size()):0;
    } catch(const std::exception& error) {fail(env,error);return 0;}
}
extern "C" JNIEXPORT jstring JNICALL Java_com_fluxx_android_engine_IsolatedFontNative_inspect(JNIEnv* env,jobject,jbyteArray input) {
    try {
        // Android isolated/app-zygote isolated UIDs occupy 90000..99999 per user.
        const auto uid=getuid()%100000;
        if(uid<90000 || uid>99999) throw std::invalid_argument("Imported font parsing requires an isolated process");
        if(!input || env->GetArrayLength(input)<=0 || env->GetArrayLength(input)>16*1024*1024)
            throw std::invalid_argument("Font files must be nonempty and at most 16 MiB");
        std::vector<uint8_t> bytes(static_cast<size_t>(env->GetArrayLength(input)));
        env->GetByteArrayRegion(input,0,static_cast<jsize>(bytes.size()),reinterpret_cast<jbyte*>(bytes.data()));
        if(env->ExceptionCheck()) return nullptr;
        const auto metadata=fluxx::text::inspectFont(bytes.data(),bytes.size());
        // Metadata may contain non-ASCII family/style names; JNI modified UTF-8 is unsuitable.
        auto raw=env->NewByteArray(static_cast<jsize>(metadata.size()));
        if(!raw) return nullptr;
        env->SetByteArrayRegion(raw,0,static_cast<jsize>(metadata.size()),reinterpret_cast<const jbyte*>(metadata.data()));
        const auto cls=env->FindClass("java/lang/String");const auto ctor=env->GetMethodID(cls,"<init>","([BLjava/lang/String;)V");
        const auto encoding=env->NewStringUTF("UTF-8");
        return static_cast<jstring>(env->NewObject(cls,ctor,raw,encoding));
    } catch(const std::exception& error) { fail(env,error);return nullptr; }
}
extern "C" JNIEXPORT void JNICALL Java_com_fluxx_android_engine_TextNative_close(JNIEnv*, jobject, jlong id) {
    std::shared_ptr<FontManager> released;
    {
        std::lock_guard<std::mutex> lock(registryMutex);
        auto found = sessions.find(id);
        if (found != sessions.end()) { released = std::move(found->second); sessions.erase(found); }
    }
    // Last lease destroys handles/bytes after in-flight calls, outside the registry lock.
}
extern "C" JNIEXPORT jdoubleArray JNICALL Java_com_fluxx_android_engine_TextNative_metrics(JNIEnv* env, jobject, jlong id, jstring font, jbyteArray utf8,jboolean ligatures) {
    try {
        if (!utf8 || env->GetArrayLength(utf8)>static_cast<jsize>(FontManager::MaxTextBytes)) throw std::invalid_argument("Invalid text bytes");
        std::string text(static_cast<size_t>(env->GetArrayLength(utf8)), '\0');
        env->GetByteArrayRegion(utf8,0,static_cast<jsize>(text.size()),reinterpret_cast<jbyte*>(text.data()));
        if(env->ExceptionCheck()) return nullptr;
        const auto values=fluxx::text::layoutMetrics(*get(id)->shape(string(env,font),text,ligatures));
        auto result=env->NewDoubleArray(static_cast<jsize>(values.size()));
        if(result) env->SetDoubleArrayRegion(result,0,static_cast<jsize>(values.size()),values.data());
        return result;
    } catch(const std::exception& error) { fail(env,error); return nullptr; }
}
extern "C" JNIEXPORT jstring JNICALL Java_com_fluxx_android_engine_TextNative_shape(JNIEnv* env, jobject, jlong id, jstring font, jbyteArray utf8) {
    try {
        if (!utf8 || env->GetArrayLength(utf8) > static_cast<jsize>(FontManager::MaxTextBytes)) throw std::invalid_argument("Invalid text bytes");
        std::string text(static_cast<size_t>(env->GetArrayLength(utf8)), '\0');
        env->GetByteArrayRegion(utf8, 0, static_cast<jsize>(text.size()), reinterpret_cast<jbyte*>(text.data()));
        if (env->ExceptionCheck()) return nullptr;
        const auto layout = get(id)->shape(string(env, font), text);
        const auto json = fluxx::text::layoutJson(*layout);
        return env->NewStringUTF(json.c_str());
    } catch (const std::exception& error) { fail(env, error); return nullptr; }
}
extern "C" JNIEXPORT jbyteArray JNICALL Java_com_fluxx_android_engine_TextNative_preparedPacket(JNIEnv* env,jobject,jlong id,jstring font,jbyteArray bytes,jboolean ligatures,jboolean nominal) {
    try {
        if(!bytes || env->GetArrayLength(bytes)>1024*1024) throw std::invalid_argument("Invalid styled text bytes");
        std::vector<uint8_t> buffer(static_cast<size_t>(env->GetArrayLength(bytes)));
        env->GetByteArrayRegion(bytes,0,static_cast<jsize>(buffer.size()),reinterpret_cast<jbyte*>(buffer.data()));
        if(env->ExceptionCheck()) return nullptr;
        const auto input=fluxx::text::decodeStyledInput(buffer.data(),buffer.size());const auto manager=get(id);
        if(nominal && input.runs.size()!=1) throw std::invalid_argument("Nominal catalogue requires one font run");
        const auto layout=nominal?manager->nominal(input.runs[0].fontId,input.text):
            manager->shapeStyled(string(env,font),input.text,input.runs,input.uppercase,ligatures);
        const auto packet=fluxx::text::encodePreparedText(*layout,*manager);
        auto result=env->NewByteArray(static_cast<jsize>(packet.size()));
        if(result) env->SetByteArrayRegion(result,0,static_cast<jsize>(packet.size()),reinterpret_cast<const jbyte*>(packet.data()));return result;
    } catch(const std::exception& error) { fail(env,error);return nullptr; }
}
extern "C" JNIEXPORT jdoubleArray JNICALL Java_com_fluxx_android_engine_TextNative_preparedMetrics(JNIEnv* env,jobject,jbyteArray bytes) {
    try {
        if(!bytes || static_cast<size_t>(env->GetArrayLength(bytes))>fluxx::text::MaxPreparedPacketBytes) throw std::invalid_argument("Invalid prepared text bytes");
        std::vector<uint8_t> buffer(static_cast<size_t>(env->GetArrayLength(bytes)));
        env->GetByteArrayRegion(bytes,0,static_cast<jsize>(buffer.size()),reinterpret_cast<jbyte*>(buffer.data()));
        if(env->ExceptionCheck()) return nullptr;
        const auto values=fluxx::text::layoutMetrics(*fluxx::text::decodePreparedText(buffer.data(),buffer.size()));
        auto result=env->NewDoubleArray(static_cast<jsize>(values.size()));
        if(result) env->SetDoubleArrayRegion(result,0,static_cast<jsize>(values.size()),values.data());return result;
    } catch(const std::exception& error) { fail(env,error);return nullptr; }
}
extern "C" JNIEXPORT jintArray JNICALL Java_com_fluxx_android_engine_TextNative_preparedPreview(JNIEnv* env,jobject,jbyteArray input,jint width,jint height,jint colour) {
    try {
        if(!input || static_cast<size_t>(env->GetArrayLength(input))>fluxx::text::MaxPreparedPacketBytes || width<1 || width>1024 || height<1 || height>256)
            throw std::invalid_argument("Invalid font preview limits");
        std::vector<uint8_t> bytes(static_cast<size_t>(env->GetArrayLength(input)));
        env->GetByteArrayRegion(input,0,static_cast<jsize>(bytes.size()),reinterpret_cast<jbyte*>(bytes.data()));
        if(env->ExceptionCheck()) return nullptr;
        const auto layout=fluxx::text::decodePreparedText(bytes.data(),bytes.size());
        std::vector<jint> pixels(static_cast<size_t>(width)*height,0);
        const double size=std::min(32.0,height/2.0);double pen=4;unsigned line=0;
        for(size_t g=0;g<layout->glyphs.size();++g) {
            const auto& glyph=layout->glyphs[g];const auto& geometry=layout->geometry[g];const auto& sdf=*layout->externalSdfs[g];
            if(glyph.line!=line) { pen=4;line=glyph.line; }
            const double sx=size*geometry.scaleX/48,sy=size*geometry.scaleY/48;
            const double x=pen+size*geometry.xOffset/1000+sdf.left*sx;
            const double y=size*1.3+line*size*1.2-size*geometry.yOffset/1000-sdf.top*sy;
            if(sx>0 && sy>0 && sdf.width>0 && sdf.height>0) {
                const int left=static_cast<int>(std::clamp(std::floor(x),0.0,double(width)));
                const int right=static_cast<int>(std::clamp(std::ceil(x+sdf.width*sx),0.0,double(width)));
                const int top=static_cast<int>(std::clamp(std::floor(y),0.0,double(height)));
                const int bottom=static_cast<int>(std::clamp(std::ceil(y+sdf.height*sy),0.0,double(height)));
                for(int py=top;py<bottom;++py) for(int px=left;px<right;++px) {
                    const double u=std::clamp((px+.5-x)/sx-.5,0.0,double(sdf.width-1));
                    const double v=std::clamp((py+.5-y)/sy-.5,0.0,double(sdf.height-1));
                    const int a=static_cast<int>(std::floor(u)),b=static_cast<int>(std::floor(v));
                    auto sample=[&](int ix,int iy) { return sdf.pixels[static_cast<size_t>(std::clamp(iy,0,sdf.height-1))*sdf.width+std::clamp(ix,0,sdf.width-1)]/255.0; };
                    const double du=u-a,dv=v-b;
                    const double value=(sample(a,b)*(1-du)+sample(a+1,b)*du)*(1-dv)+(sample(a,b+1)*(1-du)+sample(a+1,b+1)*du)*dv;
                    const double alpha=std::clamp((value-128.0/255)*16*std::min(sx,sy)+.5,0.0,1.0);
                    const auto index=static_cast<size_t>(py)*width+px;
                    const double old=static_cast<uint32_t>(pixels[index])>>24;
                    const auto combined=static_cast<uint32_t>(std::round(alpha*255+old*(1-alpha)));
                    pixels[index]=static_cast<jint>((combined<<24)|(static_cast<uint32_t>(colour)&0xffffff));
                }
            }
            pen+=geometry.advance*size/1000;
        }
        const auto result=env->NewIntArray(width*height);
        if(result) env->SetIntArrayRegion(result,0,width*height,pixels.data());return result;
    } catch(const std::exception& error) { fail(env,error);return nullptr; }
}
extern "C" JNIEXPORT jdoubleArray JNICALL Java_com_fluxx_android_engine_TextNative_styledMetrics(JNIEnv* env,jobject,jlong id,jstring font,jbyteArray bytes,jboolean ligatures) {
    try {
        if(!bytes || env->GetArrayLength(bytes)>1024*1024) throw std::invalid_argument("Invalid styled text bytes");
        std::vector<uint8_t> buffer(static_cast<size_t>(env->GetArrayLength(bytes)));
        env->GetByteArrayRegion(bytes,0,static_cast<jsize>(buffer.size()),reinterpret_cast<jbyte*>(buffer.data()));
        if(env->ExceptionCheck()) return nullptr;
        const auto input=fluxx::text::decodeStyledInput(buffer.data(),buffer.size());
        const auto values=fluxx::text::layoutMetrics(*get(id)->shapeStyled(string(env,font),input.text,input.runs,input.uppercase,ligatures));
        auto result=env->NewDoubleArray(static_cast<jsize>(values.size()));
        if(result) env->SetDoubleArrayRegion(result,0,static_cast<jsize>(values.size()),values.data());return result;
    } catch(const std::exception& error) { fail(env,error);return nullptr; }
}
extern "C" JNIEXPORT jint JNICALL Java_com_fluxx_android_engine_ParagraphNative_wrap(JNIEnv* env,jobject,
    jobject clusters,jobject paragraphs,jobject positions,jobject lines,jint count,jint paragraphCount,jdouble width,jdouble step,jint capacity) {
    try {
        if(count<0||count>4096||paragraphCount<0||paragraphCount>256||capacity<1||capacity>256) throw std::invalid_argument("Invalid wrapping buffer counts");
        auto checked=[&](jobject buffer,jlong bytes)->void* {
            if(!buffer||env->GetDirectBufferCapacity(buffer)<bytes) throw std::invalid_argument("Invalid prepared wrapping buffer");
            auto* address=env->GetDirectBufferAddress(buffer);
            if(!address || reinterpret_cast<uintptr_t>(address)%8) throw std::invalid_argument("Unaligned wrapping buffer");
            return address;
        };
        return static_cast<jint>(fluxx::text::wrapParagraphs(static_cast<const fluxx::text::WrapCluster*>(checked(clusters,jlong(count)*24)),static_cast<uint32_t>(count),
            static_cast<const fluxx::text::WrapParagraph*>(checked(paragraphs,jlong(paragraphCount)*56)),static_cast<uint32_t>(paragraphCount),width,step,
            static_cast<double*>(checked(positions,jlong(count)*8)),static_cast<fluxx::text::WrapLine*>(checked(lines,jlong(capacity)*48)),static_cast<uint32_t>(capacity)));
    } catch(const std::exception& error) { fail(env,error);return 0; }
}
#ifdef FLUXX_TEXT_DIAGNOSTICS
extern "C" JNIEXPORT void JNICALL Java_com_fluxx_android_engine_TextDebugBridge_dump(JNIEnv* env, jobject, jlong id, jstring directory) {
    try { fluxx::text::dumpDiagnostics(*get(id), string(env, directory)); }
    catch (const std::exception& error) { fail(env, error); }
}
#endif
