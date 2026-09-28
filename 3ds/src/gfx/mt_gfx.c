#include "mt_gfx.h"

#include <3ds.h>
#include <citro2d.h>
#include <citro3d.h>

#include <math.h>
#include <string.h>

#include "scene_shbin.h"

#define DISPLAY_TRANSFER_FLAGS \
    (GX_TRANSFER_FLIP_VERT(0) | GX_TRANSFER_OUT_TILED(0) | GX_TRANSFER_RAW_COPY(0) | \
     GX_TRANSFER_IN_FORMAT(GX_TRANSFER_FMT_RGBA8) | \
     GX_TRANSFER_OUT_FORMAT(GX_TRANSFER_FMT_RGB8) | \
     GX_TRANSFER_SCALING(GX_TRANSFER_SCALE_NO))

/* True black, per docs/classic/DESIGN.md. The room comes later; the void is the
 * handbook's own stage and is what MINIMAL looks like. */
#define CLEAR_COLOR 0x000000FF

typedef struct { float pos[3], tex[2], clr[4]; } Vertex;

/* A card is a solid: six faces, two triangles each. */
#define CARD_VERTS 36
#define QUAD_VERTS 6
#define VBO_VERTS  (CARD_VERTS + QUAD_VERTS)

/*
 * A card's thickness, in card widths.
 *
 * A real one is 0.76mm against 59mm, which is 0.0129 - and on the tablet
 * `CardSolid.pileDepth` has to exaggerate that, because every z reaches the
 * screen multiplied by sin(tilt) and an honest forty-card deck came out four
 * pixels tall. Here the screen has *actual depth*: your eyes converge on the
 * edge rather than inferring it from a gradient. So this is nearly honest,
 * exaggerated only enough that a single card's white edge survives a 400x240
 * panel.
 */
#define CARD_THICK 0.020f

static DVLB_s *s_dvlb;
static shaderProgram_s s_program;
static int s_uloc_projection, s_uloc_modelview, s_uloc_matcolor;
static C3D_RenderTarget *s_top_left, *s_top_right;
static Vertex *s_vbo;

/*
 * The view, kept so each object can be composed against it.
 *
 * The shader takes `modelView`, which is one matrix, so every draw has to
 * upload `view * model`. Rebuilding the view per object would be wasteful and
 * reading the uniform back is not possible, so it is simply remembered.
 */
static C3D_Mtx s_view;

static float s_field_h = 1.0f;   /* field height in field widths */
static float s_card_w  = 0.14f;  /* card width in field widths */

static void put(Vertex *v, float x, float y, float z, float shade) {
    v->pos[0] = x; v->pos[1] = y; v->pos[2] = z;
    v->tex[0] = 0.0f; v->tex[1] = 0.0f;
    v->clr[0] = shade; v->clr[1] = shade; v->clr[2] = shade; v->clr[3] = 1.0f;
}

/** Two triangles over four corners, wound consistently. */
static int face(Vertex *v, int n,
                const float a[3], const float b[3], const float c[3], const float d[3],
                float shade) {
    put(&v[n + 0], a[0], a[1], a[2], shade);
    put(&v[n + 1], b[0], b[1], b[2], shade);
    put(&v[n + 2], c[0], c[1], c[2], shade);
    put(&v[n + 3], a[0], a[1], a[2], shade);
    put(&v[n + 4], c[0], c[1], c[2], shade);
    put(&v[n + 5], d[0], d[1], d[2], shade);
    return n + 6;
}

/*
 * The card, in its own space: one unit wide, lying in the XY plane with its
 * back on z = 0 so it rests *on* the felt rather than through it.
 *
 * The per-face shading is the whole reason a white rectangle reads as an
 * object. It is a constant here rather than a lighting calculation because a
 * card is flat - one normal per face means a per-fragment Lambert term over it
 * is constant anyway, so the arithmetic would produce exactly these numbers at
 * a cost. The room's turned solids are where that stops being true.
 */
static void build_card(Vertex *v) {
    const float w = 0.5f, h = 0.5f / CARD_ASPECT_DEFAULT, t = CARD_THICK;
    int n = 0;

    const float ftl[3] = { -w,  h, t }, ftr[3] = {  w,  h, t };
    const float fbr[3] = {  w, -h, t }, fbl[3] = { -w, -h, t };
    const float btl[3] = { -w,  h, 0 }, btr[3] = {  w,  h, 0 };
    const float bbr[3] = {  w, -h, 0 }, bbl[3] = { -w, -h, 0 };

    n = face(v, n, ftl, ftr, fbr, fbl, 1.00f);   /* the face, full brightness */
    n = face(v, n, btr, btl, bbl, bbr, 0.34f);   /* the back, in its own shade */
    n = face(v, n, btl, btr, ftr, ftl, 0.58f);   /* top edge */
    n = face(v, n, bbr, bbl, fbl, fbr, 0.50f);   /* bottom edge */
    n = face(v, n, btl, ftl, fbl, bbl, 0.66f);   /* left edge */
    n = face(v, n, btr, bbr, fbr, ftr, 0.62f);   /* right edge */
    (void)n;
}

/** A unit quad in the XY plane, for the desk and the ruled zones. */
static void build_quad(Vertex *v) {
    const float a[3] = { -0.5f,  0.5f, 0 }, b[3] = {  0.5f,  0.5f, 0 };
    const float c[3] = {  0.5f, -0.5f, 0 }, d[3] = { -0.5f, -0.5f, 0 };
    face(v, 0, a, b, c, d, 1.0f);
}

bool mt_gfx_init(void) {
    s_dvlb = DVLB_ParseFile((u32 *)scene_shbin, scene_shbin_size);
    if (!s_dvlb) return false;
    shaderProgramInit(&s_program);
    shaderProgramSetVsh(&s_program, &s_dvlb->DVLE[0]);

    s_uloc_projection = shaderInstanceGetUniformLocation(s_program.vertexShader, "projection");
    s_uloc_modelview  = shaderInstanceGetUniformLocation(s_program.vertexShader, "modelView");
    s_uloc_matcolor   = shaderInstanceGetUniformLocation(s_program.vertexShader, "matColor");

    s_vbo = (Vertex *)linearAlloc(sizeof(Vertex) * VBO_VERTS);
    if (!s_vbo) return false;
    build_card(s_vbo);
    build_quad(s_vbo + CARD_VERTS);

    /* Depth is wanted, so these are made by hand: C2D_CreateScreenTarget asks
     * for no depth buffer, which is right for the 2-D bottom screen and would
     * silently paint the stage in draw order. */
    s_top_left  = C3D_RenderTargetCreate(MT_SCREEN_H, MT_TOP_W, GPU_RB_RGBA8, GPU_RB_DEPTH24_STENCIL8);
    s_top_right = C3D_RenderTargetCreate(MT_SCREEN_H, MT_TOP_W, GPU_RB_RGBA8, GPU_RB_DEPTH24_STENCIL8);
    if (!s_top_left || !s_top_right) return false;
    C3D_RenderTargetSetOutput(s_top_left,  GFX_TOP, GFX_LEFT,  DISPLAY_TRANSFER_FLAGS);
    C3D_RenderTargetSetOutput(s_top_right, GFX_TOP, GFX_RIGHT, DISPLAY_TRANSFER_FLAGS);
    return true;
}

void mt_gfx_exit(void) {
    if (s_vbo) linearFree(s_vbo);
    shaderProgramFree(&s_program);
    if (s_dvlb) DVLB_Free(s_dvlb);
}

/** Mat fractions to the world, where the field is one unit wide about the origin. */
static void mat_to_world(MtMatPoint p, float *x, float *y) {
    *x = (p.x - 0.5f);
    *y = -(p.y - 0.5f) * s_field_h;   /* +y is toward the player */
}

bool mt_gfx_begin_stage(MtEye eye, float slider, const MtCamera *camera) {
    /* At the slider's zero there is no second picture to draw. Drawing one
     * anyway costs a third of the frame to produce an identical image. */
    if (eye == MT_EYE_RIGHT && slider <= 0.0f) return false;

    C3D_RenderTarget *target = (eye == MT_EYE_LEFT) ? s_top_left : s_top_right;
    C3D_RenderTargetClear(target, C3D_CLEAR_ALL, CLEAR_COLOR, 0);
    C3D_FrameDrawOn(target);

    /* citro2d owns this state between frames, so 3-D rebinds all of it. */
    C3D_BindProgram(&s_program);

    C3D_AttrInfo *attr = C3D_GetAttrInfo();
    AttrInfo_Init(attr);
    AttrInfo_AddLoader(attr, 0, GPU_FLOAT, 3);   /* v0 = position */
    AttrInfo_AddLoader(attr, 1, GPU_FLOAT, 2);   /* v1 = texcoord */
    AttrInfo_AddLoader(attr, 2, GPU_FLOAT, 4);   /* v2 = colour   */

    C3D_BufInfo *buf = C3D_GetBufInfo();
    BufInfo_Init(buf);
    BufInfo_Add(buf, s_vbo, sizeof(Vertex), 3, 0x210);

    C3D_TexEnv *env = C3D_GetTexEnv(0);
    C3D_TexEnvInit(env);
    C3D_TexEnvSrc(env, C3D_Both, GPU_PRIMARY_COLOR, 0, 0);
    C3D_TexEnvFunc(env, C3D_Both, GPU_REPLACE);

    /*
     * Culling off, deliberately.
     *
     * A card is a thing you look at from both sides - a face-down card seen
     * from under the table edge is a real view here in a way it never was on a
     * tablet - and the depth buffer already does the hiding. It also removes
     * the single most expensive failure available to a renderer written without
     * a device to test on: one face wound the wrong way is invisible, and
     * invisible geometry looks exactly like a matrix bug.
     */
    C3D_CullFace(GPU_CULL_NONE);

    float tx, ty;
    mat_to_world(camera->target, &tx, &ty);

    float e = C3D_AngleFromDegrees(camera->elevation);
    float s = C3D_AngleFromDegrees(camera->spin);
    float horizontal = cosf(e) * camera->distance;

    C3D_FVec eye_pos = FVec3_New(tx + horizontal * sinf(s),
                                 ty + horizontal * cosf(s),
                                 sinf(e) * camera->distance);
    C3D_FVec look_at = FVec3_New(tx, ty, 0.0f);
    C3D_FVec up      = FVec3_New(0.0f, 0.0f, 1.0f);

    C3D_Mtx projection;
    Mtx_LookAt(&s_view, eye_pos, look_at, up, false);

    /*
     * The interaxial is the user's own comfort control and has no other correct
     * source. `screen` is the convergence distance and is set to the distance
     * to what the camera is aimed at, so the table sits *in* the glass and the
     * cards lift out of it - the arrangement that reads as depth rather than as
     * a headache.
     */
    float iod = (eye == MT_EYE_LEFT ? -1.0f : 1.0f) * slider * 0.040f;
    Mtx_PerspStereoTilt(&projection, C3D_AngleFromDegrees(45.0f), C3D_AspectRatioTop,
                        0.05f, 40.0f, iod, camera->distance, false);

    C3D_FVUnifMtx4x4(GPU_VERTEX_SHADER, s_uloc_projection, &projection);
    return true;
}

static void draw_mesh(int first, int count, const C3D_Mtx *model,
                      float r, float g, float b) {
    C3D_Mtx model_view;
    Mtx_Multiply(&model_view, &s_view, model);
    C3D_FVUnifSet(GPU_VERTEX_SHADER, s_uloc_matcolor, r, g, b, 1.0f);
    C3D_FVUnifMtx4x4(GPU_VERTEX_SHADER, s_uloc_modelview, &model_view);
    C3D_DrawArrays(GPU_TRIANGLES, first, count);
}

static void draw_quad(float cx, float cy, float cz, float w, float h,
                      float r, float g, float b) {
    C3D_Mtx model;
    Mtx_Identity(&model);
    Mtx_Translate(&model, cx, cy, cz, true);
    Mtx_Scale(&model, w, h, 1.0f);
    draw_mesh(CARD_VERTS, QUAD_VERTS, &model, r, g, b);
}

/** A layout rectangle, which is in solve pixels, as a world rectangle. */
static void rect_to_world(const MtBoardLayout *layout, const MtSlot *rect,
                          float *cx, float *cy, float *w, float *h) {
    float fx = layout->field.left, fy = layout->field.top;
    float fw = layout->field.width, fh = layout->field.height;
    *w = rect->width / fw;
    *h = (rect->height / fh) * s_field_h;
    *cx = ((mt_slot_centre_x(*rect) - fx) / fw) - 0.5f;
    *cy = -(((mt_slot_centre_y(*rect) - fy) / fh) - 0.5f) * s_field_h;
}

void mt_gfx_use_layout(const MtBoardLayout *layout) {
    s_field_h = layout->field.height / layout->field.width;
    s_card_w  = layout->card_width / layout->field.width;
}

void mt_gfx_draw_table(const MtBoardLayout *layout) {
    /*
     * The desk, then the felt, then the zones - three planes a hair apart.
     *
     * They are separated in z rather than by draw order because there is a
     * depth buffer now. On the tablet `PlayScreen`'s whole invariant is that
     * draw order *is* depth, which is a rule Compose forces; here coplanar
     * surfaces would z-fight and the fix is to stop them being coplanar.
     */
    draw_quad(0.0f, 0.0f, -0.004f, 3.0f, 3.0f * s_field_h, 0.05f, 0.05f, 0.06f);
    draw_quad(0.0f, 0.0f, -0.002f, 1.06f, 1.06f * s_field_h, 0.10f, 0.10f, 0.12f);

    for (int i = 0; i < layout->slot_count; ++i) {
        float cx, cy, w, h;
        rect_to_world(layout, &layout->slots[i].rect, &cx, &cy, &w, &h);
        /* The four piles read a shade apart from the ten zones, because they
         * are somewhere cards are *kept* rather than played - the same line
         * BoardSlot draws, in the only language a table has. */
        bool pile = layout->slots[i].slot.kind != MT_SLOT_ZONE;
        float v = pile ? 0.15f : 0.20f;
        draw_quad(cx, cy, 0.0f, w * 0.94f, h * 0.94f, v, v, v * 1.15f);
    }
}

void mt_gfx_draw_card(const MtStageCard *card) {
    float wx, wy;
    mat_to_world(card->at, &wx, &wy);

    /* A pile has a real height here. On the tablet that height is notation and
     * `CardSolid.pileDepth` has to saturate it; with two eyes it is just true. */
    float wz = card->lift * s_card_w
             + (float)card->pile_depth * CARD_THICK * s_card_w;

    C3D_Mtx model;
    Mtx_Identity(&model);
    Mtx_Translate(&model, wx, wy, wz, true);
    if (mt_position_turned(card->position)) {
        Mtx_RotateZ(&model, C3D_AngleFromDegrees(90.0f), true);
    }
    Mtx_Scale(&model, s_card_w, s_card_w, s_card_w);

    float r, g, b;
    if (mt_position_face_up(card->position)) {
        r = g = b = 0.95f;
    } else {
        /* The back is kai's own artwork on the tablet; until the texture is
         * baked into romfs it is a colour, and deliberately not white - every
         * pile in the room reading as a neon rectangle is the exact failure
         * CLAUDE.md records from drawing the foil on backs. */
        r = 0.13f; g = 0.16f; b = 0.26f;
    }
    if (card->lit) {
        /* drawPrismaticInset's cool hue, on the thing under your stylus. */
        r *= 0.70f; g *= 1.05f; b *= 1.25f;
    }
    draw_mesh(0, CARD_VERTS, &model, r, g, b);
}

void mt_gfx_begin_2d(void) {
    C2D_Prepare();
}
