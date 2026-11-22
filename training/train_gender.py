"""
Модели муайян кардани зан/мард барои Muslim Shield.

Маълумот: FairFace (Kärkkäinen & Joo, 2021), литсензия CC BY 4.0
  https://github.com/joojs/fairface  ·  HuggingFace: HuggingFaceM4/FairFace (padding 0.25)
Модел: MobileNetV3-Small (torchvision, вазнҳои ImageNet), вуруд 112×112.

Содироти ONNX ба ҳамон шакле, ки GenderModel.kt интизор аст:
  вуруд  [1,3,112,112] RGB 0..255 (float)
  баромад [1,2] = [зан, мард] (logits)

Истифода:
  python train_gender.py --out gender_ff.onnx            # таълими пурра (бо интернет)
  python train_gender.py --dry-run --out /tmp/t.onnx     # санҷиши зуд бо маълумоти сохтагӣ
"""
import argparse, io, math, os, random, time

import numpy as np
import torch
import torch.nn as nn
import torch.nn.functional as F
from PIL import Image, ImageFilter

SIZE = 112
MEAN = torch.tensor([0.485, 0.456, 0.406]).view(1, 3, 1, 1)
STD = torch.tensor([0.229, 0.224, 0.225]).view(1, 3, 1, 1)


class Wrapped(nn.Module):
    """Вуруди 0..255 → нормализатсия → шабака → [зан, мард]"""
    def __init__(self, net):
        super().__init__()
        self.net = net
        self.register_buffer("mean", MEAN * 255.0)
        self.register_buffer("std", STD * 255.0)

    def forward(self, x):
        return self.net((x - self.mean) / self.std)


def build(pretrained=True, arch="small"):
    import torchvision
    if arch == "large":
        w = torchvision.models.MobileNet_V3_Large_Weights.IMAGENET1K_V2 if pretrained else None
        net = torchvision.models.mobilenet_v3_large(weights=w)
    else:
        w = torchvision.models.MobileNet_V3_Small_Weights.IMAGENET1K_V1 if pretrained else None
        net = torchvision.models.mobilenet_v3_small(weights=w)
    net.classifier[3] = nn.Linear(net.classifier[3].in_features, 2)
    return net


def augment(img: Image.Image, train: bool) -> np.ndarray:
    img = img.convert("RGB")
    if train:
        w, h = img.size
        # буриши тасодуфӣ: андозаи рӯй дар экран гуногун аст (1.3×…1.7× дар барнома)
        s = random.uniform(0.78, 1.0)
        cw, ch = int(w * s), int(h * s)
        x0 = random.randint(0, w - cw); y0 = random.randint(0, h - ch)
        img = img.crop((x0, y0, x0 + cw, y0 + ch))
        if random.random() < 0.5:
            img = img.transpose(Image.FLIP_LEFT_RIGHT)
        # рӯйҳои хурд дар экран: паст кардани сифат
        if random.random() < 0.45:
            r = random.randint(16, 64)
            img = img.resize((r, r), Image.BILINEAR)
        if random.random() < 0.15:
            img = img.filter(ImageFilter.GaussianBlur(radius=random.uniform(0.3, 1.2)))
    img = img.resize((SIZE, SIZE), Image.BILINEAR)
    a = np.asarray(img, dtype=np.float32)
    if train:
        # равшанӣ/контраст
        a = a * random.uniform(0.75, 1.25) + random.uniform(-25, 25)
        if random.random() < 0.1:
            g = a.mean(axis=2, keepdims=True); a = np.repeat(g, 3, axis=2)
        a = np.clip(a, 0, 255)
    return a.transpose(2, 0, 1)


class HFData(torch.utils.data.Dataset):
    def __init__(self, ds, train, female_label):
        self.ds, self.train, self.fl = ds, train, female_label

    def __len__(self):
        return len(self.ds)

    def __getitem__(self, i):
        r = self.ds[i]
        img = r["image"]
        if not isinstance(img, Image.Image):
            img = Image.open(io.BytesIO(img["bytes"]))
        y = 0 if r["gender"] == self.fl else 1      # 0 = зан, 1 = мард
        return torch.from_numpy(augment(img, self.train)), y


class FakeData(torch.utils.data.Dataset):
    def __init__(self, n, train):
        self.n, self.train = n, train

    def __len__(self):
        return self.n

    def __getitem__(self, i):
        y = i % 2
        img = Image.fromarray((np.random.rand(224, 224, 3) * 255 * (0.5 + 0.5 * y)).astype(np.uint8))
        return torch.from_numpy(augment(img, self.train)), y


def evaluate(model, loader):
    model.eval(); ok = n = 0
    with torch.no_grad():
        for x, y in loader:
            p = model(x).argmax(1)
            ok += (p == y).sum().item(); n += len(y)
    model.train()
    return ok / max(1, n)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", required=True)
    ap.add_argument("--epochs", type=int, default=6)
    ap.add_argument("--batch", type=int, default=128)
    ap.add_argument("--hours", type=float, default=4.3, help="ҳадди вақт")
    ap.add_argument("--dry-run", action="store_true")
    ap.add_argument("--arch", default="small", choices=["small", "large"])
    ap.add_argument("--lr", type=float, default=1.5e-3)
    a = ap.parse_args()
    t0 = time.time()
    torch.set_num_threads(max(1, os.cpu_count() or 1))

    if a.dry_run:
        tr, va = FakeData(512, True), FakeData(128, False)
        model = Wrapped(build(pretrained=False, arch=a.arch)); a.epochs = 1
    else:
        from datasets import load_dataset
        ds = load_dataset("HuggingFaceM4/FairFace", "0.25")
        feat = ds["train"].features["gender"]
        names = getattr(feat, "names", None)
        print("gender feature:", feat)
        fl = names.index("Female") if names else 1
        tr, va = HFData(ds["train"], True, fl), HFData(ds["validation"], False, fl)
        model = Wrapped(build(pretrained=True, arch=a.arch))
    print(f"train={len(tr)} val={len(va)}", flush=True)

    dl = torch.utils.data.DataLoader(tr, batch_size=a.batch, shuffle=True, num_workers=4, drop_last=True,
                                     persistent_workers=True)
    dv = torch.utils.data.DataLoader(va, batch_size=256, shuffle=False, num_workers=4)
    opt = torch.optim.AdamW(model.parameters(), lr=a.lr, weight_decay=0.03)
    steps = a.epochs * len(dl)
    sched = torch.optim.lr_scheduler.OneCycleLR(opt, max_lr=a.lr, total_steps=max(1, steps), pct_start=0.1)
    best = 0.0
    model.train()
    for ep in range(a.epochs):
        for i, (x, y) in enumerate(dl):
            loss = F.cross_entropy(model(x), y, label_smoothing=0.05)
            opt.zero_grad(); loss.backward(); opt.step(); sched.step()
            if i % 50 == 0:
                print(f"ep {ep} step {i}/{len(dl)} loss {loss.item():.3f} t={(time.time()-t0)/60:.1f}m", flush=True)
            if time.time() - t0 > a.hours * 3600:
                break
        acc = evaluate(model, dv)
        print(f"== epoch {ep} val_acc {acc:.4f} t={(time.time()-t0)/60:.1f}m", flush=True)
        if acc >= best:
            best = acc
            torch.save(model.state_dict(), a.out + ".pt")
        if time.time() - t0 > a.hours * 3600:
            print("time limit reached"); break

    model.load_state_dict(torch.load(a.out + ".pt"))
    model.eval()
    torch.onnx.export(model, torch.zeros(1, 3, SIZE, SIZE), a.out, input_names=["input"], output_names=["logits"],
                      opset_version=13, dynamo=False)
    with open(a.out + ".txt", "w") as f:
        f.write(f"FairFace(0.25) validation accuracy: {best:.4f}\nepochs: {a.epochs}\narch: {a.arch}\n")
    print("best val acc", best)


if __name__ == "__main__":
    main()
