package com.example.pvp.validation.mixin;
import com.example.pvp.validation.PearlPhaseProbe;
import com.example.pvp.arena.party.PartyRoundContext;
import com.example.pvp.arena.party.PearlHurlRound;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(targets="com.example.pvp.arena.party.PearlHurlRound",remap=false)
public abstract class PearlRoundProbeMixin {
    @Inject(method="tick",at=@At("HEAD"),remap=false)
    private void before(PartyRoundContext c,int elapsed,CallbackInfo ci){PearlPhaseProbe.round("round_head",(PearlHurlRound)(Object)this,c,elapsed);}
    @Inject(method="tick",at=@At("TAIL"),remap=false)
    private void after(PartyRoundContext c,int elapsed,CallbackInfo ci){PearlPhaseProbe.round("round_tail",(PearlHurlRound)(Object)this,c,elapsed);}
}
