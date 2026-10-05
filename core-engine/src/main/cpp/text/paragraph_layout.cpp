#include "paragraph_layout.h"
#include <algorithm>
#include <cmath>
#include <stdexcept>

namespace fluxx::text {
uint32_t wrapParagraphs(const WrapCluster* clusters,uint32_t count,const WrapParagraph* paragraphs,
    uint32_t paragraphCount,double boxWidth,double lineStep,double* positions,WrapLine* lines,uint32_t capacity) {
    if(count>4096 || paragraphCount>256 || !capacity || capacity>256 || !std::isfinite(boxWidth)||boxWidth<0||!std::isfinite(lineStep))
        throw std::invalid_argument("Text wrapping exceeds 256 lines per layer; widen the box or reduce paragraphs");
    for(uint32_t i=0;i<count;++i) if(!std::isfinite(clusters[i].advance)||!std::isfinite(clusters[i].tracking)||clusters[i].space>1||clusters[i].breakAfter>1)
        throw std::invalid_argument("Invalid prepared wrapping cluster");
    uint32_t previous=0,used=0;double top=0;
    for(uint32_t p=0;p<paragraphCount;++p) {
        const auto& s=paragraphs[p];
        if(s.begin!=previous||s.end<s.begin||s.end>count||s.alignment>6) throw std::invalid_argument("Invalid prepared paragraph coverage");
        for(double v:{s.left,s.right,s.first,s.before,s.after}) if(!std::isfinite(v)) throw std::invalid_argument("Invalid paragraph spacing");
        previous=s.end;if(p) top+=s.before;uint32_t start=s.begin;bool first=true;
        do {
            if(used==capacity) throw std::invalid_argument("Text wrapping exceeds 256 lines per layer; widen the box or reduce paragraphs");
            const double left=s.left+(first?s.first:0),available=boxWidth==0?0:std::max(0.0,boxWidth-left-s.right);
            uint32_t end=s.end;
            if(boxWidth>0) {
                double width=0;uint32_t lastBreak=start;
                for(uint32_t i=start;i<s.end;++i) {
                    width+=clusters[i].advance+(i>start?clusters[i-1].tracking:0);
                    if(width>available&&!clusters[i].space) { end=lastBreak>start?lastBreak:(i>start?i:i+1);break; }
                    if(clusters[i].breakAfter) lastBreak=i+1;
                }
            }
            uint32_t inkEnd=end;while(inkEnd>start&&clusters[inkEnd-1].space) --inkEnd;
            double advance=0;uint32_t spaces=0;
            for(uint32_t i=start;i<inkEnd;++i) { advance+=clusters[i].advance+(i>start?clusters[i-1].tracking:0);if(clusters[i].space) ++spaces; }
            const bool justified=boxWidth>0&&spaces&&(s.alignment==6||(end!=s.end&&s.alignment>=3));
            const double expansion=justified?std::max(0.0,(available-advance)/spaces):0;
            const unsigned alignment=s.alignment>=3?(s.alignment==6?0:s.alignment-3):s.alignment;
            const double alignedWidth=advance+spaces*expansion;
            double origin=left;if(boxWidth>0&&!justified) origin+=(available-alignedWidth)*(alignment==1?.5:alignment==2?1:0);
            double x=origin;
            for(uint32_t i=start;i<end;++i) { positions[i]=x;x+=clusters[i].advance+(i+1<end?clusters[i].tracking:0);if(i<inkEnd&&clusters[i].space) x+=expansion; }
            if(!std::isfinite(x)||!std::isfinite(top)||!std::isfinite(origin)||!std::isfinite(alignedWidth)) throw std::overflow_error("Text paragraph arithmetic exceeds finite bounds");
            lines[used++]={start,end,p,0,origin,alignedWidth,top,expansion};top+=lineStep;start=end;first=false;
        } while(start<s.end);
        top+=s.after;
    }
    if(previous!=count) throw std::invalid_argument("Prepared paragraphs do not cover clusters");
    if(boxWidth==0) {
        double reference=0;
        for(uint32_t l=0;l<used;++l) reference=std::max(reference,lines[l].origin+lines[l].advance+paragraphs[lines[l].paragraph].right);
        for(uint32_t l=0;l<used;++l) {
            auto& line=lines[l];const auto& p=paragraphs[line.paragraph];
            const unsigned a=p.alignment>=3?(p.alignment==6?0:p.alignment-3):p.alignment;
            const double delta=(reference-p.right-line.origin-line.advance)*(a==1?.5:a==2?1:0);
            line.origin+=delta;for(uint32_t c=line.begin;c<line.end;++c) positions[c]+=delta;
        }
    }
    return used;
}
ParagraphLayout layoutParagraphs(const std::vector<BreakCluster>& clusters,
    const std::vector<ParagraphFormat>& paragraphs,double boxWidth,double lineStep) {
    if(!std::isfinite(boxWidth)||boxWidth<0||!std::isfinite(lineStep)) throw std::invalid_argument("Invalid text box width or leading");
    if(clusters.size()>16384||paragraphs.size()>4097) throw std::invalid_argument("Paragraph layout exceeds prepared text limits");
    for(const auto& cluster:clusters) if(!std::isfinite(cluster.advance)) throw std::invalid_argument("Nonfinite cluster advance");
    ParagraphLayout result;result.positions.resize(clusters.size());
    uint32_t previous=0;double top=0;
    for(size_t p=0;p<paragraphs.size();++p) {
        const auto& style=paragraphs[p];
        if(style.begin!=previous||style.end<style.begin||style.end>clusters.size()||static_cast<uint32_t>(style.alignment)>6)
            throw std::invalid_argument("Invalid paragraph cluster coverage");
        for(double value:{style.left,style.right,style.first,style.before,style.after})
            if(!std::isfinite(value)) throw std::invalid_argument("Nonfinite paragraph format");
        previous=style.end;
        if(p) top+=style.before;
        uint32_t start=style.begin;bool first=true;
        do {
            const double left=style.left+(first?style.first:0);
            const double available=boxWidth==0?0:std::max(0.0,boxWidth-left-style.right);
            uint32_t end=style.end;
            if(boxWidth>0) {
                double width=0;uint32_t lastBreak=start;
                for(uint32_t i=start;i<style.end;++i) {
                    width+=clusters[i].advance;
                    // Hanging spaces neither force a wrap nor lose logical membership.
                    if(width>available&&!clusters[i].space) {
                        end=lastBreak>start?lastBreak:(i>start?i:i+1);break;
                    }
                    if(clusters[i].breakAfter) lastBreak=i+1;
                }
            }
            uint32_t inkEnd=end;while(inkEnd>start&&clusters[inkEnd-1].space) --inkEnd;
            double advance=0;uint32_t spaces=0;
            for(uint32_t i=start;i<inkEnd;++i) { advance+=clusters[i].advance;if(clusters[i].space) ++spaces; }
            const bool last=end==style.end;
            const bool justified=boxWidth>0&&spaces>0&&
                (style.alignment==ParagraphAlign::JustifyAll||(!last&&static_cast<uint32_t>(style.alignment)>=3));
            const double expansion=justified?std::max(0.0,(available-advance)/spaces):0;
            const double alignedWidth=advance+spaces*expansion;
            unsigned alignment=static_cast<unsigned>(style.alignment);
            if(alignment>=3) alignment=alignment==6?0:alignment-3;
            double origin=left;
            if(boxWidth>0&&!justified) origin+=(available-alignedWidth)*(alignment==1?.5:alignment==2?1:0);
            double x=origin;
            for(uint32_t i=start;i<end;++i) {
                result.positions[i]=x;x+=clusters[i].advance;
                if(i<inkEnd&&clusters[i].space) x+=expansion;
            }
            if(!std::isfinite(x)||!std::isfinite(top)||!std::isfinite(origin)||!std::isfinite(alignedWidth))
                throw std::overflow_error("Paragraph layout geometry exceeds finite range");
            result.lines.push_back({start,end,static_cast<uint32_t>(p),origin,alignedWidth,top,expansion});
            top+=lineStep;start=end;first=false;
        } while(start<style.end);
        top+=style.after;
    }
    if(previous!=clusters.size()) throw std::invalid_argument("Paragraphs do not cover shaped clusters");
    return result;
}
}
