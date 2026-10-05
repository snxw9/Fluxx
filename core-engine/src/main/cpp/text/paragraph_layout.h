#pragma once
#include <cstdint>
#include <vector>

namespace fluxx::text {
enum class ParagraphAlign : uint32_t { Left, Centre, Right, JustifyLastLeft, JustifyLastCentre, JustifyLastRight, JustifyAll };
struct ParagraphFormat {
    uint32_t begin=0,end=0;
    ParagraphAlign alignment=ParagraphAlign::Left;
    double left=0,right=0,first=0,before=0,after=0;
};
struct BreakCluster { double advance=0;bool space=false,breakAfter=false; };
struct VisualLine {
    uint32_t begin=0,end=0,paragraph=0;
    double origin=0,advance=0,top=0,wordExpansion=0;
};
struct ParagraphLayout { std::vector<VisualLine> lines;std::vector<double> positions; };
// Preparation-only greedy layout over shaped clusters. Box zero means point text;
// overflow is retained. Trailing spaces hang outside the alignment/justification width.
ParagraphLayout layoutParagraphs(const std::vector<BreakCluster>& clusters,
    const std::vector<ParagraphFormat>& paragraphs,double boxWidth,double lineStep);
}
