"""檢查程式碼用到的 messages.yml / pdf.yml 鍵都存在（開發用，不會打包）。"""
import re
import sys
import pathlib
import yaml

root = pathlib.Path(sys.argv[1])
src = "\n".join(p.read_text(encoding="utf-8") for p in (root / "src/main/java").rglob("*.java"))


def flatten(d, prefix=""):
    out = {}
    for k, v in d.items():
        key = f"{prefix}.{k}" if prefix else str(k)
        if isinstance(v, dict):
            out.update(flatten(v, key))
        else:
            out[key] = v
    return out


ok = True
for lang in ("zh_tw", "zh_cn", "en_us"):
    mpath = root / f"src/main/resources/defaults/{lang}/messages.yml"
    ppath = root / f"src/main/resources/defaults/{lang}/pdf.yml"
    if not mpath.exists():
        print(f"[{lang}] messages.yml missing")
        ok = False
        continue
    m = flatten(yaml.safe_load(mpath.read_text(encoding="utf-8")))
    p = flatten(yaml.safe_load(ppath.read_text(encoding="utf-8")))
    mkeys = set(m)
    pkeys = set(p)

    def has(key):
        return key in mkeys or (key + ".name") in mkeys

    missing = []
    literals = set(re.findall(r'"((?:gui|common|time|currency|input|link|penalty|claim|shop|auction|trade|admin|command|update|discord|item)\.[a-z0-9_.-]+)"', src))
    config_like = re.compile(r'^(discord\.(admin\.(channel-id|per-trade|daily-summary\..*)|require-link\..*|personal\.enabled|send-interval-ms|peak-threshold|merge-window-minutes|link-refresh-seconds|token)|auction\.(paused|seq)|shop\.yml|item\.)')
    for k in sorted(literals):
        if k.endswith(".") or k.endswith(".yml") or k.startswith("penalty.ban-") or k.startswith("penalty.fine-") or k == "discord.admin.":
            continue
        if config_like.match(k):
            continue
        if k.startswith("discord.admin..") or (k.startswith("discord.") and k.count(".") < 2):
            continue
        if k.startswith("discord.") and not k.endswith(".line") and k not in ("discord.footer",):
            for suffix in (".title", ".description"):
                if k.endswith(".combined.line"):
                    continue
                if (k + suffix) not in mkeys and k not in mkeys:
                    missing.append(k + suffix)
            continue
        if k.startswith("gui.auction.confirm-buy"):
            continue
        if not has(k) and not any(x.startswith(k + ".") for x in mkeys):
            missing.append(k)
    dynamic = (
        [f"gui.settings.notify.{k}.name" for k in ["purchase", "won", "sold", "listed", "removed", "expired", "outbid"]]
        + [f"shop.period.{k}" for k in ["none", "daily", "weekly", "total"]]
        + [f"trade.type.{k}" for k in ["shop_buy", "list", "bid", "sale", "sale_bid", "cancel", "force_remove", "expire", "penalty"]]
        + [f"claim.source.{k}" for k in ["bought", "won", "expired", "cancelled", "removed", "returned", "income", "refund"]]
        + [f"gui.auction.sort.{k}" for k in ["newest", "ending", "price_asc", "price_desc"]]
        + [f"gui.admin.home.{k}-locked.name" for k in ["shop", "auction", "log"]]
        + ["gui.auction.confirm-buy.title", "gui.auction.confirm-buy.confirm.name", "gui.auction.confirm-buy.cancel.name",
           "discord.admin.paused.title", "discord.admin.resumed.title", "item.durability", "discord.dm.combined.line", "discord.daily.line"]
    )
    for k in dynamic:
        if k not in mkeys:
            missing.append(k)
    pdf_literals = set(re.findall(r'(?:t|tx|text)\("((?:fields|totals|values|modes|trade-types|notes|sections|columns)\.[a-z0-9-]+)"', src))
    pdf_literals |= set(re.findall(r'"text\.((?:fields|totals|values|modes|trade-types|notes|sections|columns|types)\.[a-z0-9-]+)"', src))
    pmissing = [k for k in sorted(pdf_literals) if "text." + k not in pkeys]
    for t in ["shop-purchase", "auction-buy", "auction-won", "auction-sold", "auction-listed", "auction-outbid", "auction-expired",
              "auction-removed", "penalty", "combined", "daily"]:
        for f in ("title", "subtitle", "stamp", "color"):
            if f"text.types.{t}.{f}" not in pkeys:
                pmissing.append(f"types.{t}.{f}")
    for k in ["list", "bid", "sale", "sale-bid", "cancel", "force-remove", "expire", "penalty", "shop-buy"]:
        if f"text.trade-types.{k}" not in pkeys:
            pmissing.append("trade-types." + k)
    if missing or pmissing:
        ok = False
        print(f"[{lang}] messages missing: {missing}")
        print(f"[{lang}] pdf missing: {pmissing}")
    else:
        print(f"[{lang}] OK ({len(mkeys)} message keys, {len(pkeys)} pdf keys)")
    # 三種語言的鍵要一致
    if lang != "zh_tw":
        base_m = flatten(yaml.safe_load((root / "src/main/resources/defaults/zh_tw/messages.yml").read_text(encoding="utf-8")))
        base_p = flatten(yaml.safe_load((root / "src/main/resources/defaults/zh_tw/pdf.yml").read_text(encoding="utf-8")))
        dm = set(base_m) ^ mkeys
        dp = set(base_p) ^ pkeys
        if dm or dp:
            ok = False
            print(f"[{lang}] key set differs from zh_tw: messages {sorted(dm)[:20]} pdf {sorted(dp)[:20]}")
sys.exit(0 if ok else 1)
